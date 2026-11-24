const pageRequests = {urls: ["http://*/*", "https://*/*"], types: ["main_frame"]};
const connections = new Map();
const lastPeers = new Map();

browser.webRequest.onBeforeRequest.addListener(request => {
  if (request.tabId >= 0) {
    connections.set(request.tabId, {id: request.requestId, url: request.url});
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
  details.ready = browser.webRequest.getSecurityInfo(request.requestId).catch(() => null).then(info => Object.assign(details, {
    http: request.statusLine?.split(" ")[0] || "",
    cipher: info?.cipherSuite || "",
    keyExchange: info?.keaGroupName || "",
    tls: info?.protocolVersion || "",
    state: info?.state || "",
    fromCache: Boolean(request.fromCache)
  }));
  await details.ready;
}, pageRequests, ["blocking"]);

browser.webRequest.onResponseStarted.addListener(request => {
  const details = connections.get(request.tabId);
  if (details?.id !== request.requestId || details.url !== request.url) return;
  recordPeer(details, request);
  details.fromCache = Boolean(request.fromCache);
  details.http ||= request.statusLine?.split(" ")[0] || "";
}, pageRequests);

browser.runtime.onMessage.addListener((message, sender) => {
  if (message !== "connection" || sender.frameId !== 0 || !sender.tab || !sender.url) return;
  const details = connections.get(sender.tab.id);
  if (!details || new URL(sender.url).href.split("#")[0] !== new URL(details.url).href.split("#")[0]) return;
  return Promise.resolve(details.ready).then(() => {
    if (connections.get(sender.tab.id) !== details) return;
    const {id, ready, ...report} = details;
    return report;
  });
});

browser.tabs.onRemoved.addListener(tabId => {
  connections.delete(tabId);
  lastPeers.delete(tabId);
});
