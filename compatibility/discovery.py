#!/usr/bin/env python3
"""Stable Kotlin discovery and the rolling minor-family support window."""

import calendar
from datetime import date
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

import registry


def central_versions():
    url = 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/maven-metadata.xml'
    with urllib.request.urlopen(url, timeout=60) as response:
        nodes = ET.fromstring(response.read()).findall('./versioning/versions/version')
    result = set()
    for node in nodes:
        try:
            if registry.version(node.text) >= (2, 2, 0):
                result.add(node.text)
        except ValueError:
            pass
    return sorted(result, key=registry.version)


def available(version):
    for artifact in ('kotlin-compiler-embeddable', 'kotlin-gradle-plugin'):
        for extension in ('pom', 'jar'):
            url = f'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/{artifact}/{version}/{artifact}-{version}.{extension}'
            try:
                with urllib.request.urlopen(urllib.request.Request(url, method='HEAD'), timeout=30):
                    pass
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    return False
                raise
    return True


def supported_versions(versions, releases, today):
    """Patches share the first stable minor release's 18-calendar-month window."""
    result = []
    for version in versions:
        major, minor, _ = registry.version(version)
        first = releases[f'{major}.{minor}.0']
        year, month = divmod(first.year * 12 + first.month - 1 + 18, 12)
        expiry = date(year, month + 1, min(first.day, calendar.monthrange(year, month + 1)[1]))
        if first <= today < expiry:
            result.append(version)
    return result
