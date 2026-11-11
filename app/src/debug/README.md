Chat is a development-only feature. Its two implementations (`chat` and `ai/agentic`),
request/batch inference adapters, and Gemma workflow live in this source set. Gradle
compiles them for debug only. The bundled ML Kit text reader is also a
`debugImplementation` dependency.

The shared app uses `DevelopmentFeatures` slots and `DevelopmentIndexes` hooks.
`src/release` supplies an empty feature provider and discards obsolete, regenerable
development cache databases when repairing library indexes. Shared analysis,
embedding search, visual similarity, and model downloads remain in `src/main`.

`assembleRelease` and `bundleRelease` run `verifyReleaseFeatureExclusion`, checking
the actual Kotlin compiler inputs/outputs and the release dependency graph. This
boundary does not depend on hiding UI or on R8 removing unreachable Chat classes.
