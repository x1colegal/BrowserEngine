(() => {
  let details;
  let generation = 0;

  async function report() {
    const current = ++generation;
    for (const delay of [0, 100, 500, 1500]) {
      if (delay) await new Promise(resolve => setTimeout(resolve, delay));
      if (current !== generation) return;
      const latest = await browser.runtime.sendMessage("connection").catch(() => null);
      if (current !== generation) return;
      if (latest) details = latest;
      if (details) {
        const received = await browser.runtime.sendNativeMessage("connection", details).catch(() => false);
        if (received && details.http) return;
      }
    }
  }

  report();
  window.addEventListener("pageshow", () => {
    report();
    browser.runtime.sendNativeMessage("connection", {
      event: "pageshow",
      host: location.hostname,
      userAgent: navigator.userAgent,
      width: window.innerWidth,
      screenWidth: screen.width,
      viewport: document.querySelector('meta[name="viewport"]')?.content?.slice(0, 200) || "",
      nextHopProtocol: performance.getEntriesByType("navigation")[0]?.nextHopProtocol || "",
      serviceWorker: Boolean(navigator.serviceWorker?.controller),
      workerStart: performance.getEntriesByType("navigation")[0]?.workerStart || 0
    }).catch(() => {});
  });
  window.addEventListener("pagehide", () => generation++);
})();
