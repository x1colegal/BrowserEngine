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
      const navProtocol = performance.getEntriesByType("navigation")[0]?.nextHopProtocol || "";
      const payload = details ? {...details, navProtocol} : (navProtocol ? {url: location.href, navProtocol} : null);
      if (payload) {
        const received = await browser.runtime.sendNativeMessage("connection", payload).catch(() => false);
        if (received && (payload.http || payload.tls)) return;
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

  function activeEditable() {
    let el = document.activeElement;
    while (el && el.shadowRoot && el.shadowRoot.activeElement) {
      el = el.shadowRoot.activeElement;
    }
    if (!el || el === document.body || el === document.documentElement) return null;
    const editable = el.isContentEditable ||
      /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) ||
      el.getAttribute?.("role") === "textbox";
    return editable ? el : null;
  }

  function keepFocusedVisible() {
    const el = activeEditable();
    if (!el) return;
    const vh = Math.min(window.innerHeight || Infinity, window.visualViewport?.height || Infinity);
    if (!Number.isFinite(vh) || vh <= 0) return;
    const rect = el.getBoundingClientRect();
    if (rect.bottom > vh - 8 || rect.top < 0) {
      try { el.scrollIntoView({ block: "center", inline: "nearest" }); } catch (_) {}
    }
  }

  let focusScrollTimer = 0;
  function scheduleKeepFocusedVisible() {
    keepFocusedVisible();
    clearTimeout(focusScrollTimer);
    focusScrollTimer = setTimeout(keepFocusedVisible, 180);
  }

  document.addEventListener("focusin", () => {
    setTimeout(scheduleKeepFocusedVisible, 80);
    setTimeout(keepFocusedVisible, 350);
  }, true);
  window.addEventListener("resize", scheduleKeepFocusedVisible);
  window.visualViewport?.addEventListener("resize", scheduleKeepFocusedVisible);
})();
