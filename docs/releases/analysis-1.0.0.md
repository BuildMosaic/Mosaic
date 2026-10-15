# Mosaic Analysis 1.0.0

Released October 15, 2026.

Analysis adds optional Canvas verification and architecture graphs through the
`org.buildmosaic.analysis` Gradle plugin. Its version is independent of Runtime
and outside the Runtime BOM. The plugin selects its compiler integration automatically.

## Upgrade from 0.7.0

Use Analysis 1.0.0 with Runtime 1.0.0 and matching Kotlin compiler/KGP versions;
see [compatibility](https://BuildMosaic.org/reference/compatibility/).
Rebuild analyzed libraries and applications: **metadata contract version 6**
replaces older summaries. Analysis understands Core semantic revision 1 and rejects
unsupported revisions rather than trusting their results.

Analysis APIs, configuration, metadata, and reports remain provisional. Missing
dependency metadata and unsupported paths remain `UNVERIFIED`; incompatible metadata
fails. STANDARD warns on uncertainty; STRICT also fails it. Both fail proven errors.

See the [analysis guide](https://BuildMosaic.org/guides/analysis/) for installation
and the [Runtime notes](../../docs/releases/runtime-1.0.0.md) for API migration.
