const pageRequests = {urls: ["http://*/*", "https://*/*"], types: ["main_frame"]};
const connections = new Map();
const lastPeers = new Map();
const lastOriginReports = new Map();

function saveOriginReport(tabId, report) {
  if (tabId < 0 || !report?.url) return;
  if (!report.http && !report.tls && !report.cipher) return;
  try {
    const origin = new URL(report.url).origin;
    let map = lastOriginReports.get(tabId);
    if (!map) {
      map = new Map();
      lastOriginReports.set(tabId, map);
    }
    const {id, ready, ...clean} = report;
    map.set(origin, clean);
  } catch (_) {}
}

browser.webRequest.onBeforeRequest.addListener(request => {
  if (request.tabId >= 0) {
    const existing = connections.get(request.tabId);
    if (existing && existing.id === request.requestId) {
      existing.url = request.url;
    } else {
      connections.set(request.tabId, {id: request.requestId, url: request.url});
    }
  }
}, pageRequests);

browser.webRequest.onBeforeSendHeaders.addListener(request => {
  const details = connections.get(request.tabId);
  if (details?.id !== request.requestId || details.url !== request.url) return;
  details.requestUserAgent = request.requestHeaders?.find(
    header => header.name.toLowerCase() === "user-agent"
  )?.value || "";
}, pageRequests, ["requestHeaders"]);

function recordPeer(details, request) {
  if (!request.fromCache && request.ip) {
    details.ip = request.ip;
    details.cachedIp = false;
    lastPeers.set(request.tabId, {origin: new URL(request.url).origin, ip: request.ip});
  } else if (!details.ip && request.fromCache) {
    const previous = lastPeers.get(request.tabId);
    if (previous?.origin === new URL(request.url).origin) {
      details.ip = previous.ip;
      details.cachedIp = true;
    }
  }
}

browser.webRequest.onHeadersReceived.addListener(async request => {
  if (request.tabId < 0) return;
  let details = connections.get(request.tabId);
  if (details?.id !== request.requestId || details.url !== request.url) {
    details = {id: request.requestId, url: request.url};
    connections.set(request.tabId, details);
  }
  recordPeer(details, request);
  details.ready = browser.webRequest.getSecurityInfo(request.requestId).catch(() => null).then(info => {
    Object.assign(details, {
      http: request.statusLine?.split(" ")[0] || "",
      cipher: info?.cipherSuite || "",
      keyExchange: info?.keaGroupName || "",
      tls: info?.protocolVersion || "",
      state: info?.state || "",
      fromCache: Boolean(request.fromCache)
    });
    saveOriginReport(request.tabId, details);
    return details;
  });
  await details.ready;
}, pageRequests, ["blocking"]);

browser.webRequest.onBeforeRedirect.addListener(request => {
  const details = connections.get(request.tabId);
  if (details && details.id === request.requestId && request.redirectUrl) {
    details.url = request.redirectUrl;
  }
}, pageRequests);

browser.webRequest.onResponseStarted.addListener(request => {
  const details = connections.get(request.tabId);
  if (details?.id !== request.requestId || details.url !== request.url) return;
  recordPeer(details, request);
  details.fromCache = Boolean(request.fromCache);
  details.http ||= request.statusLine?.split(" ")[0] || "";
  saveOriginReport(request.tabId, details);
}, pageRequests);

browser.runtime.onMessage.addListener((message, sender) => {
  if (message !== "connection" || sender.frameId !== 0 || !sender.tab || !sender.url) return;
  const senderOrigin = new URL(sender.url).origin;
  const details = connections.get(sender.tab.id);
  const fallback = lastOriginReports.get(sender.tab.id)?.get(senderOrigin);
  if (!details) return fallback ? Promise.resolve(fallback) : undefined;
  let detailsOrigin = "";
  try { detailsOrigin = new URL(details.url).origin; } catch (_) {}
  if (senderOrigin !== detailsOrigin) {
    return fallback ? Promise.resolve(fallback) : undefined;
  }
  return Promise.resolve(details.ready).then(() => {
    const {id, ready, ...report} = details;
    if (!report.http && !report.tls && !report.cipher && fallback) {
      return {...fallback, url: sender.url, fromCache: true};
    }
    return report;
  });
});

browser.tabs.onRemoved.addListener(tabId => {
  connections.delete(tabId);
  lastPeers.delete(tabId);
  lastOriginReports.delete(tabId);
});

const mediaRequests = {urls: ["http://*/*", "https://*/*"], tabId: -1};
const bgConnections = new Map();
const probeControllers = new Map();
let mediaPort = null;

function sendMediaReport(details) {
  if (!mediaPort) return;
  try {
    mediaPort.postMessage(details);
  } catch (_) {}
}

function connectMediaPort() {
  try {
    const port = browser.runtime.connectNative("media_connection");
    mediaPort = port;
    port.onMessage.addListener(msg => {
      if (!msg || msg.action !== "probe" || !msg.url) return;
      const targetUrl = String(msg.url);
      const prev = probeControllers.get(targetUrl);
      if (prev) {
        try { prev.abort(); } catch (_) {}
      }
      const ac = new AbortController();
      probeControllers.set(targetUrl, ac);
      fetch(targetUrl, {
        method: "GET",
        headers: {"Range": "bytes=0-0"},
        cache: "no-store",
        credentials: "include",
        signal: ac.signal
      }).then(resp => {
        try { resp.body?.cancel(); } catch (_) {}
      }).catch(() => {}).finally(() => {
        if (probeControllers.get(targetUrl) === ac) {
          probeControllers.delete(targetUrl);
        }
      });
    });
    port.onDisconnect.addListener(() => {
      if (mediaPort === port) mediaPort = null;
    });
  } catch (_) {}
}

connectMediaPort();

browser.webRequest.onHeadersReceived.addListener(async request => {
  const details = {
    url: request.url,
    http: request.statusLine?.split(" ")[0] || "",
    ip: (!request.fromCache && request.ip) ? request.ip : "",
    fromCache: Boolean(request.fromCache),
    cachedIp: false
  };
  try {
    const info = await browser.webRequest.getSecurityInfo(request.requestId);
    if (info) {
      details.cipher = info.cipherSuite || "";
      details.keyExchange = info.keaGroupName || "";
      details.tls = info.protocolVersion || "";
      details.state = info.state || "";
    }
  } catch (_) {}
  if (details.http || details.tls || details.cipher || details.ip) {
    bgConnections.set(request.requestId, details);
    sendMediaReport(details);
  }
}, mediaRequests, ["blocking"]);

browser.webRequest.onResponseStarted.addListener(request => {
  const details = bgConnections.get(request.requestId);
  if (details) {
    bgConnections.delete(request.requestId);
    let changed = false;
    if (!request.fromCache && request.ip && details.ip !== request.ip) {
      details.ip = request.ip;
      details.fromCache = false;
      changed = true;
    }
    const statusHttp = request.statusLine?.split(" ")[0] || "";
    if (!details.http && statusHttp) {
      details.http = statusHttp;
      changed = true;
    }
    if (changed) sendMediaReport(details);
  }
  const ac = probeControllers.get(request.url);
  if (ac) {
    probeControllers.delete(request.url);
    try { ac.abort(); } catch (_) {}
  }
}, mediaRequests);

browser.webRequest.onErrorOccurred.addListener(request => {
  bgConnections.delete(request.requestId);
}, mediaRequests);
