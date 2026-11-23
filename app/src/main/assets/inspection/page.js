(() => {
  let details;
  async function report() {
    const latest = await browser.runtime.sendMessage("connection");
    if (latest) details = latest;
    if (details) await browser.runtime.sendNativeMessage("connection", details);
  }

  report().catch(() => {});
  window.addEventListener("pageshow", () => report().catch(() => {}));
})();
