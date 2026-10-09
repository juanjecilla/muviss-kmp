// Registers the `muvissVersion` extension: the git-derived app version, defined
// once in MuvissVersion. Applied by every module that stamps a version —
// :app:androidApp, :app:desktopApp and :core:common.
extensions.add(MuvissVersion::class.java, "muvissVersion", MuvissVersion(providers, rootDir))
