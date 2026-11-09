import java.util.SortedSet

/**
 * The comparison `verifyJlinkModules` makes between what jdeps says the desktop app **requires**, the
 * roots `jlinkModules` **declares** and what those roots **resolve** to in the runtime image, apart from
 * the two external calls that produce them (#490). The pure half is what a test can feed a bad answer.
 *
 * Missing is fatal; the other two are reports. See `VerifyJlinkModules` for why.
 */
class JlinkVerdict(required: Set<String>, declared: Set<String>, resolved: Set<String>) {
    /** Required and not in the resolved image: a NoClassDefFoundError in the packaged app. */
    val missing: SortedSet<String> = (required - resolved).toSortedSet()

    /**
     * Required, resolved, but not a declared root: present only because another root happens to require
     * it. `java.base` is mandated in every image and never declared, so it is not interesting.
     */
    val transitiveOnly: SortedSet<String> = ((required - declared).intersect(resolved) - "java.base").toSortedSet()

    /** Declared but never named by jdeps: fine if reached by reflection or JNI, dead weight otherwise. */
    val unused: SortedSet<String> = (declared - required).toSortedSet()

    val ok: Boolean get() = missing.isEmpty()
}
