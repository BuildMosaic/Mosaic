import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark as b

FIXTURE = Path(__file__).parent / 'fixtures' / 'wrk2-output.txt'


class BenchmarkLogicTest(unittest.TestCase):
    def test_proc_stat_and_status(self):
        fields = ['S'] + ['0'] * 49
        fields[11], fields[12], fields[19] = '120', '30', '777'
        self.assertEqual(b.parse_proc_stat('123 (java worker) x) ' + ' '.join(fields)), (120, 30, 777))
        self.assertEqual(b.parse_proc_status('VmRSS:\t2048 kB\nVmHWM:\t4096 kB\n'),
                         {'VmRSS': 2.0, 'VmHWM': 4.0})
        with self.assertRaises(ValueError):
            b.parse_proc_stat('bad')

    def test_cpu_and_same_window_denominator(self):
        self.assertEqual(b.cpu_seconds((100, 50, 9), (350, 200, 9), 100), 4.0)
        self.assertEqual(b.cpu_ms_per_request(4.0, 200), 20.0)
        self.assertIsNone(b.cpu_ms_per_request(4.0, 0))
        parsed = b.parse_wrk2(FIXTURE.read_text(), 2)
        self.assertEqual(b.cpu_ms_per_request(4.0, parsed['steady_completed_requests']), 5.0)
        self.assertNotEqual(parsed['steady_completed_requests'], parsed['full_run_requests'])
        with self.assertRaisesRegex(ValueError, 'reused'):
            b.cpu_seconds((1, 1, 9), (2, 2, 10), 100)

    def test_wrk2_command(self):
        generator = {'binary': '/usr/local/bin/wrk2'}
        command = b.wrk2_command(generator, 800, 52, 128, 4, 8080, [1, 3])
        self.assertEqual(command[:6], ['taskset', '-c', '1,3', 'stdbuf', '-oL', '-eL'])
        self.assertEqual(command[6], generator['binary'])
        self.assertIn('-L', command)
        self.assertIn('-U', command)
        self.assertEqual(command[command.index('-R') + 1], '800')
        self.assertEqual(command[command.index('-d') + 1], '52s')
        self.assertEqual(b.wrk2_total_duration(30), 41)
        self.assertEqual(b.wrk2_total_duration(7), 18)

    def test_deterministic_request_body_and_input_rotation(self):
        for route, body in [('light', '{"customerId":7}'), ('aggregate', '{"customerId":7}'),
                            ('batching', '{"catalogId":7}'), ('coalescing', '{"catalogId":7}'), ('compute', '{"seed":7}')]:
            self.assertEqual(b.request_body(route, 7), body)
        self.assertEqual(b.INPUTS, (1, 7, 42, 99))
        with self.assertRaises(ValueError):
            b.request_body('unknown', 7)

    def test_calibration_and_corrected_uncorrected_histograms(self):
        text = FIXTURE.read_text()
        result = b.parse_wrk2(text, 2)
        self.assertEqual(result['steady_completed_requests'], 800)
        self.assertEqual(result['full_run_requests'], 1824)
        self.assertEqual(result['scheduling_latency_p50_ms'], 0.95)
        self.assertEqual(result['scheduling_latency_p95_ms'], 1.703)
        self.assertEqual(result['http_latency_p95_ms'], 0.33)
        self.assertGreater(result['scheduling_latency_p99_ms'], result['http_latency_p99_ms'])
        self.assertEqual(result['socket_errors'], 0)
        self.assertEqual(result['non_2xx_responses'], 0)
        self.assertEqual(sum(result['lua_100ms_bins'].values()), result['lua_window_requests'])
        self.assertEqual(result['lua_100ms_bins'], {0: 400, 1: 200, 2: 200})
        with self.assertRaisesRegex(ValueError, 'calibrations'):
            b.parse_wrk2(text.replace('Thread calibration:', 'Calibration:', 1), 2)
        with self.assertRaisesRegex(ValueError, 'histogram counts disagree'):
            b.parse_wrk2(text.replace('Total count    =          800',
                                     'Total count    =          801', 1), 2)
        with self.assertRaisesRegex(ValueError, 'p95, mean, or count'):
            b.parse_wrk2(text.replace('1.703     0.950000', '1.703     0.960000'), 2)

    def test_wrk2_socket_and_http_error_parsing(self):
        text = FIXTURE.read_text().replace('MOSAIC_NON2XX thread=1 count=0',
                                           'MOSAIC_NON2XX thread=1 count=3')
        text += '\nSocket errors: connect 1, read 2, write 0, timeout 4\n'
        result = b.parse_wrk2(text, 2)
        self.assertEqual((result['socket_errors'], result['non_2xx_responses']), (7, 3))
        checked = b.validate_steady_window(result, 100, 7, 8, 8)
        self.assertFalse(checked['valid_comparison_point'])
        self.assertIn('socket or non-2xx errors', checked['integrity_warnings'])

    def test_cumulative_count_accepts_wrk2_duration_units(self):
        for duration in ('18.08s', '1.18m', '2.34h'):
            with self.subTest(duration=duration):
                text = FIXTURE.read_text().replace('1824 requests in 18.08s',
                                                   f'1824 requests in {duration}')
                self.assertEqual(b.parse_wrk2(text, 2)['full_run_requests'], 1824)

    def test_low_rate_catchup_wave_cannot_hide_in_one_second(self):
        for rate in (100, 400, 600):
            with self.subTest(rate=rate):
                metric = b.parse_wrk2(FIXTURE.read_text(), 2)
                bins = {i: rate // 10 for i in range(80)}
                # One 200 ms pause, then a compensating 200 ms burst. The
                # full count and every one-second count remain exact.
                bins.update({30: 0, 31: 0, 32: rate // 5, 33: rate // 5})
                self.assertEqual(sum(bins[i] for i in range(30, 40)), rate)
                metric.update(steady_completed_requests=rate * 8,
                              lua_window_requests=rate * 8, lua_100ms_bins=bins,
                              scheduling_latency_p99_ms=687, http_latency_p99_ms=285)
                checked = b.validate_steady_window(metric, rate, 7, 8, 8)
                self.assertFalse(checked['valid_comparison_point'])
                self.assertIn('200 ms pacing wave', ' '.join(checked['integrity_warnings']))

    def test_schedule_tail_anomaly_despite_smooth_dispatch(self):
        metric = b.parse_wrk2(FIXTURE.read_text(), 2)
        metric.update(steady_completed_requests=25600, lua_window_requests=25600,
                      lua_100ms_bins={i: 320 for i in range(80)},
                      scheduling_latency_p99_ms=1200, http_latency_p99_ms=0.2)
        checked = b.validate_steady_window(metric, 3200, 7, 8, 8)
        self.assertFalse(checked['valid_comparison_point'])
        self.assertEqual(checked['integrity_warnings'],
                         ['scheduling p99 shows a large delay anomaly'])
        self.assertEqual(metric['http_latency_p99_ms'], 0.2)

    def test_steady_window_rate_and_pacing(self):
        metric = b.parse_wrk2(FIXTURE.read_text(), 2)
        # Exact total counts cannot compensate for bursty dispatch.
        self.assertFalse(b.validate_steady_window(metric, 100, 7, 8, 8)['valid_comparison_point'])
        metric['lua_100ms_bins'] = {i: 10 for i in range(80)}
        good = b.validate_steady_window(metric, 100, 7, 8.01, 8)
        self.assertTrue(good['valid_comparison_point'])
        self.assertAlmostEqual(good['steady_expected_requests'], 801)
        short = b.validate_steady_window(metric, 100, 7, 7, 8)
        self.assertFalse(short['valid_comparison_point'])
        self.assertIn('completed count differs', short['integrity_warnings'][0])
        self.assertFalse(b.validate_steady_window(metric, 100, 7, 10, 8)['valid_comparison_point'])
        self.assertFalse(b.validate_steady_window({**metric, 'lua_window_requests': 650},
                                                100, 7, 8, 8)['valid_comparison_point'])
        wave = dict(metric)
        wave['lua_100ms_bins'] = {**metric['lua_100ms_bins'], **{i: 0 for i in range(10, 20)}}
        self.assertFalse(b.validate_steady_window(wave, 100, 7, 8, 8)['valid_comparison_point'])
        high = {**metric, 'steady_completed_requests': 25600, 'lua_window_requests': 24000,
                'lua_100ms_bins': {i: 320 for i in range(75)}}
        self.assertTrue(b.validate_steady_window(high, 3200, 7, 8, 7.5)['valid_comparison_point'])
        high['lua_100ms_bins'][20] = 0
        high['lua_100ms_bins'][21] = 640
        self.assertIn('100 ms pacing wave', ' '.join(
            b.validate_steady_window(high, 3200, 7, 8, 7.5)['integrity_warnings']))

    def test_generator_discovery_and_metadata(self):
        version = SimpleNamespace(stdout='wrk 4.0.0\nUsage: wrk <options> <url>\n', stderr='', returncode=1)
        with patch.object(b.shutil, 'which', return_value='/usr/local/bin/wrk2'), \
                patch.object(b.subprocess, 'run', return_value=version) as run, \
                patch.object(b.Path, 'read_bytes', return_value=b'wrk2 test'):
            generator = b.discover_wrk2()
        self.assertEqual(generator['binary'], '/usr/local/bin/wrk2')
        self.assertEqual(generator['reported_version'], 'wrk 4.0.0')
        run.assert_called_once_with([generator['binary'], '--version'],
                                    capture_output=True, text=True)
        with patch.object(b.shutil, 'which', return_value='/usr/local/bin/wrk2'), \
                patch.object(b.subprocess, 'run', return_value=SimpleNamespace(stdout='', stderr='bad executable')), \
                self.assertRaisesRegex(ValueError, 'did not report its version'):
            b.discover_wrk2()
        with patch.object(b.shutil, 'which', return_value=None), \
                self.assertRaisesRegex(ValueError, 'required on PATH'):
            b.discover_wrk2()
        args = SimpleNamespace(cpu_work=20000, skip_build=True)
        with patch.object(b, 'command_output', return_value='test'), \
                patch.object(b.shutil, 'which', return_value='/usr/bin/java'), \
                patch.object(b.os, 'sched_getaffinity', return_value={0, 1, 2, 3}):
            metadata = b.metadata({}, args, None, None, list(b.DEFAULT_JVM),
                                  {'commit': 'abc', 'status': ''}, generator)
            pinned = b.metadata({}, args, [0, 1], [2, 3], list(b.DEFAULT_JVM),
                                {'commit': 'abc', 'status': ''}, generator)
        self.assertEqual(metadata['generator'], generator)
        self.assertEqual(metadata['application_cpu_affinity'], [0, 1, 2, 3])
        self.assertEqual(pinned['load_cpu_affinity'], [2, 3])
        self.assertIn('actual request dispatch', metadata['latency_semantics']['http'])
        self.assertIn('diagnostic only', metadata['latency_semantics']['scheduling'])

    def test_invalid_pair_excluded_from_aggregation(self):
        base = {'route': 'light', 'latency_profile': 'zero', 'offered_rps': 100,
                'repetition': 1, 'steady_completed_requests': 800, 'scheduling_latency_p99_ms': 1,
                'http_latency_p99_ms': 0.5,
                'cpu_ms_per_request': 0.1, 'cpu_us_per_request': 100, 'cpu_core_equivalents': 0.1,
                'generator_cpu_core_equivalents': 0.1, 'socket_errors': 0,
                'non_2xx_responses': 0, 'integrity_warnings': []}
        with tempfile.TemporaryDirectory() as temporary:
            rows = [{**base, 'variant': 'direct', 'valid_comparison_point': True},
                    {**base, 'variant': 'mosaic', 'valid_comparison_point': False}]
            b.write_load_summary(Path(temporary), rows)
            self.assertEqual(len((Path(temporary) / 'summary.csv').read_text().splitlines()), 1)
            summary = (Path(temporary) / 'summary.md').read_text()
            self.assertIn('**INVALID**', summary)
            self.assertIn('HTTP p99 ms', summary)
            self.assertIn('Scheduling p99 ms', summary)
            self.assertIn('scheduling latency is diagnostic only', summary)

    def test_paired_aggregation_median_and_range(self):
        rows = []
        for variant, values in [('direct', [10, 20, 100, 200]),
                                ('mosaic', [11, 24, 90, 240])]:
            for repetition, value in enumerate(values, 1):
                rows.append({**dict.fromkeys(b.FIELDS, 0),
                             'route': 'compute', 'latency_profile': 'zero', 'offered_rps': 100,
                             'variant': variant, 'repetition': repetition, 'valid_comparison_point': True,
                             'cpu_ms_per_request': value, 'cpu_us_per_request': value * 1000})
        results = b.aggregate(rows)
        result = next(x for x in results if x['metric'] == 'cpu_ms_per_request')
        self.assertEqual(result['paired_repetitions'], 4)
        self.assertEqual(result['direct_median'], 60)
        self.assertEqual(result['mosaic_median'], 57)
        self.assertEqual(result['median_paired_absolute_difference'], 2.5)
        self.assertEqual(result['min_paired_absolute_difference'], -10)
        self.assertEqual(result['max_paired_absolute_difference'], 40)
        self.assertEqual(result['paired_difference_iqr'], 14.75)
        micros = next(x for x in results if x['metric'] == 'cpu_us_per_request')
        self.assertEqual(micros['median_paired_absolute_difference'], 2500)
        self.assertEqual(b.aggregate([rows[0]]), [])
        with self.assertRaisesRegex(ValueError, 'duplicate paired result'):
            b.aggregate(rows + [rows[0]])

    def test_config_and_common_controls(self):
        cfg = {'cases': {'light': {'zero': [100, 400]}}, 'repetitions': 4,
               'measurement_seconds': 30, 'calibration_timeout_seconds': 15,
               'tail_seconds': 10, 'wrk2_threads': 4, 'wrk2_connections': 128}
        b.validate_config(cfg)
        b.validate_config({**cfg, 'cases': {'coalescing': {'zero': [800], 'service': [400]}}})
        for change in ({'wrk2_connections': 130}, {'measurement_seconds': 1.5},
                       {'calibration_timeout_seconds': 10}, {'cases': {'compute': {'service': [100]}}}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                b.validate_config({**cfg, **change})
        b.validate_config({**cfg, 'cases': {'light': {'zero': [10000]}}})
        self.assertEqual(b.validate_affinity(None, None), (None, None))
        self.assertEqual(b.paired_order(1), ('direct', 'mosaic'))
        self.assertEqual(b.paired_order(2), ('mosaic', 'direct'))
        self.assertEqual(b.parse_cpu_set('0-2,4'), [0, 1, 2, 4])
        with self.assertRaisesRegex(ValueError, 'disjoint'):
            b.validate_affinity('0-2', '2-3')
        b.verify_jvm_options(list(b.DEFAULT_JVM))
        with self.assertRaises(ValueError):
            b.verify_jvm_options(['-XX:+AlwaysPreTouch'])

    def test_process_sampler(self):
        with tempfile.TemporaryDirectory() as temporary:
            sampler = b.ProcessSampler(os.getpid(), Path(temporary) / 'samples.csv')
            sampler.start()
            sampler.stop()
            self.assertTrue(sampler.rows)
            self.assertGreater(sampler.rows[0]['rss_mib'], 0)

    def test_explicit_warmup_same_jvm_precedes_measurement(self):
        events = []
        process = SimpleNamespace(pid=123)
        values = dict.fromkeys(b.FIELDS, 0)
        values.update(steady_completed_requests=800, valid_comparison_point=True, integrity_warnings=[])
        args = SimpleNamespace(readiness_timeout=30, cpu_work=20000)
        config = dict(measurement_seconds=7, wrk2_connections=8, wrk2_threads=2)
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(b, 'launch', return_value=(process, 8080, 0, None, None)) as launch, \
                patch.object(b, 'wait_ready', return_value=0.1), \
                patch.object(b, 'warm_application', side_effect=lambda *a: events.append(('warm', a[3])) or {'duration_seconds': 15}), \
                patch.object(b, 'verify_java_process', side_effect=lambda pid, *a: events.append(('verify', pid))), \
                patch.object(b, 'run_wrk2', side_effect=lambda pid, *a: events.append(('measure', pid)) or values), \
                patch.object(b, 'terminate'):
            row = b.load_case('direct', 'light', 'zero', 100, 1, 1, config, Path(tmp), args, [], None, None, {})
        launch.assert_called_once()
        self.assertEqual(events, [('warm', 8080), ('verify', 123), ('measure', 123)])
        self.assertEqual(row['application_pid'], 123)
        self.assertEqual(row['warmup']['duration_seconds'], 15)

    def test_warmup_http_errors_fail_before_measurement(self):
        with tempfile.TemporaryDirectory() as tmp:
            def run(command, **kw):
                kw['stdout'].write('100 requests in 3.00s\nMOSAIC_NON2XX thread=1 count=1\n')
                return SimpleNamespace(returncode=0)
            config = dict(warmup_seconds=3, wrk2_connections=8, wrk2_threads=1, tail_seconds=5)
            with patch.object(b.subprocess, 'run', side_effect=run), self.assertRaisesRegex(ValueError, 'warmup failed'):
                b.warm_application('light', 100, 1, 8080, config, Path(tmp), None, {'binary': '/wrk2'})
            self.assertEqual(json.loads((Path(tmp)/'warmup/result.json').read_text())['non_2xx_responses'], 1)

    def test_resume_retains_valid_pairs_and_retries_whole_failed_pair(self):
        base = {**dict.fromkeys(b.FIELDS, 0), 'route':'light', 'latency_profile':'zero', 'offered_rps':100,
                'steady_completed_requests':800, 'socket_errors':0, 'non_2xx_responses':0,
                'generator_cpu_core_equivalents':0, 'integrity_warnings':[]}
        config = dict(cases={'light':{'zero':[100]}}, repetitions=2)
        calls = []
        def first(variant, route, profile, rps, repetition, *args):
            calls.append((repetition, variant))
            return {**base, 'variant':variant, 'repetition':repetition, 'valid_comparison_point':repetition==1}
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            with patch.object(b, 'load_case', side_effect=first), self.assertRaisesRegex(ValueError, 'run invalid'):
                b.run_load_suite(config, path, None, [], None, None, {})
            ledger = json.loads((path/'pair-attempts.json').read_text())
            self.assertEqual([a['status'] for a in ledger], ['VALID','INVALID'])
            self.assertEqual(len(b.aggregate(json.loads((path/'load-results.json').read_text()))), len(b.FIELDS))
            self.assertEqual(calls, [(1,'direct'),(1,'mosaic'),(2,'mosaic')])
            calls.clear()
            def second(variant, route, profile, rps, repetition, *args):
                calls.append((repetition,variant))
                return {**base,'variant':variant,'repetition':repetition,'valid_comparison_point':True}
            with patch.object(b, 'load_case', side_effect=second):
                b.run_load_suite(config, path, None, [], None, None, {})
            self.assertEqual(calls, [(2,'mosaic'),(2,'direct')])
            ledger = json.loads((path/'pair-attempts.json').read_text())
            self.assertEqual([a['status'] for a in ledger], ['VALID','INVALID','VALID'])
            rows = json.loads((path/'load-results.json').read_text())
            self.assertEqual(b.aggregate(rows)[0]['paired_repetitions'], 2)
            self.assertEqual(rows[-1]['attempt'], 2)
            with patch.object(b, 'load_case') as load:
                b.run_load_suite(config, path, None, [], None, None, {})
            load.assert_not_called()

    def test_resume_rejects_source_or_configuration_change(self):
        meta = {'git': {'commit':'abc','status':'','dirty':False}, 'jvm_options':list(b.DEFAULT_JVM),
                'benchmark_configuration':{'warmup_seconds':15}}
        b.verify_resume(meta,meta)
        for change in [{'jvm_options':['-Xmx64m']}, {'git':{'commit':'def'}},
                       {'benchmark_configuration':{'warmup_seconds':0}}, {'git_after':{'commit':'abc','status':'dirty'}}]:
            with self.assertRaisesRegex(ValueError, 'resume metadata mismatch'):
                b.verify_resume({**meta,**change},meta)

    def test_missing_optional_host_fingerprint_is_safe(self):
        with patch.object(b, 'optional_text', return_value=None), patch.object(b.shutil,'which',return_value=None):
            fingerprint = b.host_fingerprint()
        self.assertIsNone(fingerprint['clocksource'])
        self.assertEqual(fingerprint['microcode_revisions'], [])

    def test_startup_aggregation(self):
        rows = [{'variant': 'direct', 'repetition': 1, 'readiness_seconds': 0.1},
                {'variant': 'mosaic', 'repetition': 1, 'readiness_seconds': 0.12},
                {'variant': 'mosaic', 'repetition': 2, 'readiness_seconds': 0.24},
                {'variant': 'direct', 'repetition': 2, 'readiness_seconds': 0.2}]
        summary, pairs = b.aggregate_startup(rows)
        self.assertEqual(len(pairs), 2)
        self.assertAlmostEqual(summary['paired']['median_mosaic_minus_direct_ms'], 30)
        self.assertAlmostEqual(summary['direct']['median_ms'], 150)
        self.assertAlmostEqual(summary['paired']['min_mosaic_minus_direct_ms'], 20)
        self.assertAlmostEqual(summary['paired']['max_mosaic_minus_direct_ms'], 40)
        with tempfile.TemporaryDirectory() as temporary:
            b.write_startup_summary(Path(temporary), rows)
            text = (Path(temporary) / 'startup' / 'summary.md').read_text()
            self.assertIn('30.000 ms', text)


if __name__ == '__main__':
    unittest.main()
