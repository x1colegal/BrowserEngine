(() => {
  let port;
  let supported = false;
  let inPip = false;
  let scheduled = false;
  let controlsVideo = null;

  const send = message => {
    try { port?.postMessage(message); } catch (_) {}
  };

  function presentPip(enabled) {
    inPip = enabled;
    document.documentElement?.classList.toggle("browserengine-pip", enabled);
    if (enabled) {
      const fullscreen = document.fullscreenElement;
      const video = fullscreen instanceof HTMLVideoElement ? fullscreen : fullscreen?.querySelector("video");
      if (video?.controls) {
        controlsVideo = video;
        video.controls = false;
      }
    } else if (controlsVideo) {
      controlsVideo.controls = true;
      controlsVideo = null;
    }
  }

  function connect() {
    try {
      const connection = browser.runtime.connectNative("video");
      port = connection;
      connection.onMessage.addListener(message => {
        supported = message.supported;
        if (inPip !== message.inPip) presentPip(message.inPip);
        queueRefresh();
      });
      connection.onDisconnect.addListener(() => {
        if (port !== connection) return;
        port = null;
        supported = false;
        presentPip(false);
        queueRefresh();
      });
    } catch (_) {}
  }

  function makePipButton(player) {
    const button = document.createElement("button");
    button.className = "vjs-control vjs-button browserengine-vjs-pip";
    button.type = "button";
    button.title = "Picture-in-picture";
    button.setAttribute("aria-label", button.title);
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    const path = document.createElementNS(svg.namespaceURI, "path");
    path.setAttribute("d", "M3 4h18a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm0 2v12h18V6zm9 5h7v5h-7z");
    path.setAttribute("fill-rule", "evenodd");
    svg.append(path);
    button.append(svg);
    button.addEventListener("click", async event => {
      event.stopPropagation();
      try {
        if (document.fullscreenElement !== player &&
            !document.fullscreenElement?.contains(player)) await player.requestFullscreen();
        send("pip");
      } catch (_) {}
    });
    return button;
  }

  function refresh() {
    scheduled = false;
    for (const bar of document.querySelectorAll(".video-js .vjs-control-bar")) {
      const player = bar.closest(".video-js");
      if (!player?.querySelector("video")) continue;
      let button = bar.querySelector(".browserengine-vjs-pip");
      if (supported && !button) {
        button = makePipButton(player);
        bar.insertBefore(button, bar.querySelector(".vjs-fullscreen-control"));
      }
      if (!supported) button?.remove();
    }
  }

  function queueRefresh() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(refresh);
  }

  connect();
  window.addEventListener("pageshow", () => { if (!port) connect(); });
  window.addEventListener("pagehide", () => {
    const previous = port;
    port = null;
    previous?.disconnect();
    presentPip(false);
  });
  document.addEventListener("fullscreenchange", queueRefresh);
  new MutationObserver(queueRefresh).observe(document, {childList: true, subtree: true});
})();
