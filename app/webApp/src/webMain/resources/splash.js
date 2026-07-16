// Plain JS (not Kotlin) on purpose — this has to run and finish its job
// before the Wasm/JS module even starts downloading, so it can't depend on
// anything Kotlin/Compose produces. Removes the #splash spinner (see
// index.html) the moment Compose attaches its <canvas> to <body>, so the
// splash disappears exactly when there's something real to show instead of
// guessing a fixed delay.
(function () {
    var splash = document.getElementById("splash");
    if (!splash) return;

    function removeSplash() {
        splash.remove();
        observer.disconnect();
    }

    if (document.querySelector("canvas")) {
        removeSplash();
        return;
    }

    var observer = new MutationObserver(function () {
        if (document.querySelector("canvas")) {
            removeSplash();
        }
    });
    observer.observe(document.body, { childList: true, subtree: true });
})();
