(() => {
  let port;
  let supported = false;
  let inPip = false;
  let clonedPip = false;
  let scheduled = false;
  let controlsVideo = null;

  const send = message => {
    try { port?.postMessage(message); } catch (_) {}
  };

  let pipVideo = null;
  let pipAncestors = [];
  let pipUserPaused = false;
  let origMediaPause = null;
  let bootPip = false;
  let bootSeekSec = 0;
  let bootAutoPlay = false;
  let bootApplied = false;
  let lastTimeSentMs = 0;
  let bigBtnClicked = false;

  const isAdPlaying = () => Boolean(document.querySelector(".ad-showing, .ad-interrupting"));

  function tryAutoPlay(v) {
    if (!v || !v.paused || v.ended) return;
    const rawV = v.wrappedJSObject || v;
    if (!bigBtnClicked) {
      const bigBtn = document.querySelector(".vjs-big-play-button, .ytp-large-play-button, button[aria-label='Play']");
      if (bigBtn) {
        bigBtnClicked = true;
        try { bigBtn.click(); } catch (_) {}
      }
    }
    try {
      const p = rawV.play ? rawV.play() : v.play?.();
      if (p && typeof p.catch === "function") {
        p.catch(() => {
          try {
            const wasMuted = rawV.muted;
            rawV.muted = true;
            const p2 = rawV.play ? rawV.play() : v.play?.();
            if (p2 && typeof p2.then === "function") {
              p2.then(() => {
                setTimeout(() => { try { rawV.muted = wasMuted; } catch (_) {} }, 150);
              }).catch(() => {});
            }
          } catch (_) {}
        });
      }
    } catch (_) {}
  }

  function applyBootToVideo(v) {
    if (!bootPip || !v) return;
    if (!inPip || pipVideo !== v) presentPip(true);
    if (v.ended) return;
    if (isAdPlaying()) {
      if (bootAutoPlay && v.paused && !pipUserPaused && v.currentTime < 0.5) tryAutoPlay(v);
      return;
    }
    if (!bootApplied && v.readyState >= 1) {
      bootApplied = true;
      try {
        if (bootSeekSec > 0 && (!v.duration || bootSeekSec < v.duration) && Math.abs(v.currentTime - bootSeekSec) > 1.5) {
          v.currentTime = bootSeekSec;
        }
        if (bootAutoPlay && !pipUserPaused) tryAutoPlay(v);
      } catch (_) {}
    } else if (bootAutoPlay && v.paused && !pipUserPaused && v.currentTime < 0.5) {
      tryAutoPlay(v);
    }
  }

  function setPipPlaceholder(active, seekSec, resume) {
    const existing = document.getElementById("browserengine-pip-placeholder");
    const v = pipVideo || document.querySelector("video");
    if (active) {
      if (v) {
        if (typeof v.currentTime === "number" && !Number.isNaN(v.currentTime)) {
          send({action: "pip_time", currentTime: v.currentTime});
        }
        const rawV = v.wrappedJSObject || v;
        try { origMediaPause ? origMediaPause.call(rawV) : rawV.pause?.(); } catch (_) {}
        const host = v.parentElement || document.body;
        if (host && !existing) {
          if (getComputedStyle(host).position === "static") {
            host.style.position = "relative";
          }
          const el = document.createElement("div");
          el.id = "browserengine-pip-placeholder";
          el.className = "browserengine-pip-placeholder";
          el.textContent = "Already Playing on PiP";
          host.appendChild(el);
        }
      }
    } else {
      existing?.remove();
      if (v) {
        try {
          const rawV = v.wrappedJSObject || v;
          const player = window.wrappedJSObject?.document?.getElementById?.("movie_player");
          const hasSeek = typeof seekSec === "number" && seekSec > 0;
          if (hasSeek) {
            if (player && typeof player.seekTo === "function") {
              try { player.seekTo(seekSec, true); } catch (_) {}
            }
            if (Math.abs(v.currentTime - seekSec) > 1.5) {
              v.currentTime = seekSec;
            }
            const resync = () => {
              if (Math.abs(v.currentTime - seekSec) > 2.0) {
                try {
                  if (player && typeof player.seekTo === "function") player.seekTo(seekSec, true);
                  v.currentTime = seekSec;
                } catch (_) {}
              }
            };
            v.addEventListener("playing", resync, {once: true});
            setTimeout(resync, 250);
          }
          if (resume) {
            if (player && typeof player.playVideo === "function") {
              try { player.playVideo(); } catch (_) {}
            }
            (rawV.play ? rawV.play() : v.play?.())?.catch?.(() => {});
          }
        } catch (_) {}
      }
    }
  }
  function presentPip(enabled) {
    inPip = enabled;
    document.documentElement?.classList.toggle("browserengine-pip", enabled);
    if (enabled) {
      pipUserPaused = false;
      const fullscreen = document.fullscreenElement;
      const video = (fullscreen instanceof HTMLVideoElement ? fullscreen : fullscreen?.querySelector("video"))
        || document.querySelector("video");
      if (video) {
        pipVideo = video;
        video.classList.add("browserengine-pip-video");
        let parent = video.parentElement;
        while (parent && parent !== document.documentElement) {
          parent.classList.add("browserengine-pip-ancestor");
          pipAncestors.push(parent);
          parent = parent.parentElement;
        }
        if (video.controls) {
          controlsVideo = video;
          video.controls = false;
        }
      }
    } else {
      pipUserPaused = false;
      for (const ancestor of pipAncestors) ancestor.classList.remove("browserengine-pip-ancestor");
      pipAncestors = [];
      if (pipVideo) {
        const v = pipVideo;
        const rawV = v.wrappedJSObject || v;
        const wasPlaying = !v.paused;
        v.classList.remove("browserengine-pip-video");
        pipVideo = null;
        if (wasPlaying) {
          try {
            if (origMediaPause) {
              origMediaPause.call(rawV);
              setTimeout(() => { (rawV.play ? rawV.play() : v.play?.())?.catch?.(() => {}); }, 120);
            } else {
              const pageWin = window.wrappedJSObject;
              const Evt = pageWin?.Event || Event;
              rawV.dispatchEvent(new Evt("play"));
              rawV.dispatchEvent(new Evt("playing"));
            }
          } catch (_) {}
        }
      }
      if (controlsVideo) {
        controlsVideo.controls = true;
        controlsVideo = null;
      }
    }
  }

  function connect() {
    try {
      const connection = browser.runtime.connectNative("video");
      port = connection;
      connection.onMessage.addListener(message => {
        if (message?.action === "pip_boot") {
          bootPip = true;
          bootSeekSec = Number(message.seekSec) || 0;
          bootAutoPlay = Boolean(message.autoPlay);
          bootApplied = false;
          presentPip(true);
          applyBootToVideo(pipVideo || document.querySelector("video"));
          return;
        }
        if (message?.action === "pip_placeholder") {
          setPipPlaceholder(Boolean(message.active), Number(message.seekSec) || 0, Boolean(message.resume));
          return;
        }
        if (message?.action === "play") {
          pipUserPaused = false;
          (pipVideo || document.querySelector("video"))?.play?.();
          return;
        }
        if (message?.action === "pause") {
          pipUserPaused = true;
          const v = pipVideo || document.querySelector("video");
          const rawV = v?.wrappedJSObject || v;
          if (rawV) (origMediaPause ? origMediaPause.call(rawV) : rawV.pause?.());
          return;
        }
        if (message?.action === "fill_login") {
          const passField = document.querySelector("input[type='password']");
          if (passField) {
            const form = passField.form || passField.closest("form") || document;
            const userField = Array.from(form.querySelectorAll("input"))
              .find(i => i !== passField && !i.disabled && ["text", "email", "tel", ""].includes((i.type || "").toLowerCase()));
            const setVal = (el, val) => {
              const proto = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value");
              proto?.set?.call(el, val);
              el.dispatchEvent(new Event("input", {bubbles: true}));
              el.dispatchEvent(new Event("change", {bubbles: true}));
            };
            if (userField && message.username) setVal(userField, message.username);
            if (message.password) setVal(passField, message.password);
          }
          return;
        }
        supported = message.supported;
        const wasClonedPip = clonedPip;
        clonedPip = Boolean(message.clonedPip);
        document.documentElement?.classList.toggle("browserengine-cloned-pip", clonedPip);
        if (!wasClonedPip && clonedPip && !pipUserPaused) {
          const v = pipVideo || document.querySelector("video");
          if (v && !v.ended) {
            const ensurePlay = () => {
              if (!clonedPip || pipUserPaused || v.ended || !v.paused) return;
              const rawV = v.wrappedJSObject || v;
              try { (rawV.play ? rawV.play() : v.play?.())?.catch?.(() => {}); } catch (_) {}
            };
            setTimeout(ensurePlay, 100);
            setTimeout(ensurePlay, 300);
          }
        }
        if (wasClonedPip && !clonedPip && !pipUserPaused) {
          const v = pipVideo || document.querySelector("video");
          if (v && !v.ended && v.paused) {
            const rawV = v.wrappedJSObject || v;
            try { (rawV.play ? rawV.play() : v.play?.())?.catch?.(() => {}); } catch (_) {}
          }
        }
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
    button.addEventListener("click", event => {
      event.stopPropagation();
      send("pip");
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
      if (button) {
        button.setAttribute("aria-pressed", String(clonedPip));
        button.title = clonedPip ? "Exit picture-in-picture" : "Picture-in-picture";
        button.setAttribute("aria-label", button.title);
        button.style.color = clonedPip ? "#8ab4f8" : "";
      }
      if (!supported) button?.remove();
    }
  }

  let refreshTimer = 0;
  function queueRefresh() {
    if (scheduled || refreshTimer) return;
    scheduled = true;
    refreshTimer = setTimeout(() => {
      refreshTimer = 0;
      requestAnimationFrame(refresh);
    }, 250);
  }

  const isPip = () => Boolean(
    document.documentElement?.classList.contains("browserengine-pip") ||
    document.documentElement?.classList.contains("browserengine-cloned-pip")
  );

  try {
    const pageWin = window.wrappedJSObject;
    if (pageWin && typeof exportFunction === "function") {
      const docProto = pageWin.Document?.prototype;
      if (docProto) {
        const origHidden = Object.getOwnPropertyDescriptor(docProto, "hidden")?.get;
        const hiddenGetter = exportFunction(function() {
          return isPip() ? false : Boolean(origHidden ? origHidden.call(this) : false);
        }, pageWin);
        Object.defineProperty(docProto, "hidden", {
          configurable: true,
          enumerable: true,
          get: hiddenGetter
        });
        Object.defineProperty(docProto, "webkitHidden", {
          configurable: true,
          enumerable: true,
          get: hiddenGetter
        });
        const origVis = Object.getOwnPropertyDescriptor(docProto, "visibilityState")?.get;
        const visGetter = exportFunction(function() {
          return isPip() ? "visible" : (origVis ? origVis.call(this) : "visible");
        }, pageWin);
        Object.defineProperty(docProto, "visibilityState", {
          configurable: true,
          enumerable: true,
          get: visGetter
        });
        Object.defineProperty(docProto, "webkitVisibilityState", {
          configurable: true,
          enumerable: true,
          get: visGetter
        });
        const origHasFocus = docProto.hasFocus;
        Object.defineProperty(docProto, "hasFocus", {
          configurable: true,
          value: exportFunction(function() {
            return isPip() ? true : Boolean(origHasFocus ? origHasFocus.call(this) : true);
          }, pageWin)
        });
      }
      const etProto = pageWin.EventTarget?.prototype;
      if (etProto) {
        const origAdd = etProto.addEventListener;
        const blockedEvents = new Set(["visibilitychange", "webkitvisibilitychange", "mozvisibilitychange", "blur", "pagehide", "freeze"]);
        Object.defineProperty(etProto, "addEventListener", {
          configurable: true,
          writable: true,
          value: exportFunction(function(type, fn, opts) {
            if (blockedEvents.has(type) && typeof fn === "function") {
              const wrapped = exportFunction(function(e) {
                if (!isPip()) return fn.call(this, e);
              }, pageWin);
              return origAdd.call(this, type, wrapped, opts);
            }
            return origAdd.call(this, type, fn, opts);
          }, pageWin)
        });
      }
      const mediaProto = pageWin.HTMLMediaElement?.prototype;
      if (mediaProto && typeof mediaProto.pause === "function") {
        origMediaPause = mediaProto.pause;
        Object.defineProperty(mediaProto, "pause", {
          configurable: true,
          writable: true,
          value: exportFunction(function() {
            if (isPip() && !pipUserPaused) return;
            return origMediaPause.call(this);
          }, pageWin)
        });
      }
    }
  } catch (_) {}

  for (const evt of ["visibilitychange", "webkitvisibilitychange", "blur", "pagehide"]) {
    window.addEventListener(evt, event => {
      if (isPip()) event.stopImmediatePropagation();
    }, true);
    document.addEventListener(evt, event => {
      if (isPip()) event.stopImmediatePropagation();
    }, true);
  }

  let draftLogin = null;
  let activePassField = null;
  let lastSentKey = "";
  const userStoreKey = "be_last_user:" + location.origin;

  function isUsernameInput(el) {
    if (!(el instanceof HTMLInputElement) || el.disabled || el.readOnly) return false;
    const t = (el.type || "").toLowerCase();
    if (!["text", "email", "tel", ""].includes(t)) return false;
    if (t === "email") return true;
    const hint = ((el.autocomplete || "") + " " + (el.name || "") + " " + (el.id || "") + " " + (el.placeholder || "")).toLowerCase();
    return /user|email|login|account|identifier|phone|cpf|nick/.test(hint);
  }

  function findUsernameFor(passField, scope) {
    const form = passField?.form || passField?.closest?.("form");
    const root = form || passField?.parentElement?.parentElement?.parentElement || scope || document;
    const inputs = Array.from(root.querySelectorAll("input"));
    const passIdx = inputs.indexOf(passField);
    const beforePass = passIdx > 0 ? inputs.slice(0, passIdx).reverse() : inputs;
    const candidates = beforePass.filter(i =>
      i !== passField && !i.disabled && !i.readOnly &&
      ["text", "email", "tel", ""].includes((i.type || "").toLowerCase())
    );
    const exact = candidates.find(i => isUsernameInput(i) && i.value);
    if (exact?.value) return exact.value;
    if (form) {
      const fallback = candidates.find(i => i.value && !/lang|search|query|captcha|code/i.test((i.name || "") + " " + (i.id || "") + " " + (i.placeholder || "")));
      if (fallback?.value) return fallback.value;
    }
    try { return sessionStorage.getItem(userStoreKey) || ""; } catch (_) { return ""; }
  }

  function captureDraft(scope) {
    const root = scope || document;
    const passField = (activePassField && root.contains?.(activePassField) && activePassField.value)
      ? activePassField
      : root.querySelector?.("input[type='password']");
    if (passField && passField.value) {
      activePassField = passField;
      draftLogin = {
        origin: location.origin,
        username: findUsernameFor(passField, root),
        password: passField.value
      };
    }
  }

  function commitDraft(scope) {
    captureDraft(scope);
    if (!draftLogin || !draftLogin.password) return;
    const key = draftLogin.origin + "\n" + draftLogin.username + "\n" + draftLogin.password;
    if (key === lastSentKey) return;
    lastSentKey = key;
    send({action: "login_submit", ...draftLogin});
    draftLogin = null;
    activePassField = null;
  }

  function isLoginFocusTarget(target) {
    if (!(target instanceof HTMLInputElement) || target.disabled || target.readOnly) return false;
    const t = (target.type || "").toLowerCase();
    return t === "password" || isUsernameInput(target) ||
      (["text", "email", "tel", ""].includes(t) && Boolean(document.querySelector("input[type='password']")));
  }

  document.addEventListener("focusin", event => {
    if (isLoginFocusTarget(event.target)) {
      send({action: "login_focus", domain: location.hostname});
    } else {
      send({action: "login_blur"});
    }
  }, true);

  document.addEventListener("focusout", () => {
    setTimeout(() => {
      if (!isLoginFocusTarget(document.activeElement)) {
        send({action: "login_blur"});
      }
    }, 120);
  }, true);

  document.addEventListener("input", event => {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) return;
    const t = (target.type || "").toLowerCase();
    if (t === "password") {
      if (target.value) {
        activePassField = target;
        draftLogin = {
          origin: location.origin,
          username: findUsernameFor(target),
          password: target.value
        };
      }
    } else if (isUsernameInput(target) && target.value) {
      try { sessionStorage.setItem(userStoreKey, target.value); } catch (_) {}
      if (activePassField?.value) {
        draftLogin = { origin: location.origin, username: target.value, password: activePassField.value };
      }
    }
  }, true);

  document.addEventListener("change", event => {
    const target = event.target;
    if (target instanceof HTMLInputElement && (target.type || "").toLowerCase() === "password") {
      captureDraft(target.form || document);
    }
  }, true);

  document.addEventListener("submit", event => {
    commitDraft(event.target);
  }, true);

  document.addEventListener("keydown", event => {
    if (event.key === "Enter" && (draftLogin?.password || document.querySelector("input[type='password']")?.value)) {
      commitDraft(event.target?.form || document);
    }
  }, true);

  document.addEventListener("click", event => {
    const btn = event.target?.closest?.("button, input[type='submit'], input[type='button'], [role='button'], a");
    if (!btn) return;
    if (activePassField && (btn === activePassField || btn.contains(activePassField))) return;
    const hint = ((btn.getAttribute("aria-label") || "") + " " + (btn.title || "") + " " + (btn.className || "")).toLowerCase();
    if (/show|hide|eye|toggle|reveal|mask|clear|forgot|reset|register|signup|create|cancel|back|close|lang/.test(hint)) return;
    const text = ((btn.innerText || btn.value || btn.getAttribute("aria-label") || "")).toLowerCase();
    const form = btn.form || btn.closest?.("form");
    const isSubmitLike = btn.type === "submit" || Boolean(form && btn.tagName === "BUTTON") ||
      /log\s*in|sign\s*in|entrar|submit|ok|next|continue|auth|acessar|enviar|conectar|login/.test(text);
    if (!isSubmitLike) return;
    if (draftLogin?.password || (form || document).querySelector("input[type='password']")?.value) {
      commitDraft(form || document);
    }
  }, true);

  window.addEventListener("hashchange", () => { if (draftLogin?.password) commitDraft(document); });
  window.addEventListener("beforeunload", () => { if (draftLogin?.password) commitDraft(document); });

  connect();
  window.addEventListener("pageshow", () => { if (!port) connect(); });
  window.addEventListener("pagehide", () => {
    if (draftLogin?.password) commitDraft(document);
    const previous = port;
    port = null;
    previous?.disconnect();
    presentPip(false);
  });
  document.addEventListener("timeupdate", event => {
    const v = event.target;
    if (!(v instanceof HTMLVideoElement)) return;
    if (bootPip && !bootApplied && !isAdPlaying()) applyBootToVideo(v);
    if (isAdPlaying()) return;
    const now = Date.now();
    const intervalMs = isPip() ? 300 : 2000;
    if (now - lastTimeSentMs >= intervalMs) {
      lastTimeSentMs = now;
      send({action: "pip_time", currentTime: v.currentTime || 0});
    }
  }, true);

  document.addEventListener("loadedmetadata", event => {
    if (bootPip && event.target instanceof HTMLVideoElement) applyBootToVideo(event.target);
  }, true);

  document.addEventListener("canplay", event => {
    const v = event.target;
    if (!(v instanceof HTMLVideoElement) || !v.paused || v.ended || pipUserPaused) return;
    if (bootPip && bootAutoPlay) {
      tryAutoPlay(v);
    } else if (clonedPip) {
      const rawV = v.wrappedJSObject || v;
      try { (rawV.play ? rawV.play() : v.play?.())?.catch?.(() => {}); } catch (_) {}
    }
  }, true);

  document.addEventListener("fullscreenchange", queueRefresh);
  new MutationObserver(() => {
    queueRefresh();
    if (bootPip) applyBootToVideo(pipVideo || document.querySelector("video"));
    if (draftLogin?.password && activePassField && !document.contains(activePassField)) {
      commitDraft(document);
    }
  }).observe(document, {childList: true, subtree: true});
})();
