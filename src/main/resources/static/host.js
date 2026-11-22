import { captionView, request, sessionUrl, subscribe } from "./captions.js";

const byId = id => document.getElementById(id);
const view = captionView("preview");
const title = byId("title");
const start = byId("start");
const end = byId("end");
const resume = byId("resume");
const meter = byId("input-level");
const errorBox = byId("host-error");
const hostStatus = byId("host-status");
const previewStatus = byId("preview-status");
const sharing = byId("share");
const joinLink = byId("join-link");
const savedKey = "captionbridge:host-session";
let session;
let ended = false;
let busy = false;
let stopPreview;
let capture;
let connection;

function setError(message = "") {
  errorBox.textContent = message;
  errorBox.hidden = !message;
}

function refreshControls() {
  byId("session-form").hidden = Boolean(session);
  byId("host-actions").hidden = !session;
  byId("transcript").hidden = !ended;
  sharing.hidden = !session;
  start.disabled = busy;
  end.disabled = busy;
  end.hidden = ended;
  resume.disabled = busy;
  resume.hidden = ended || Boolean(connection);
  for (const button of document.querySelectorAll("[data-export], #delete")) button.disabled = busy;
}

function storeSession(value) {
  try {
    if (value) sessionStorage.setItem(savedKey, JSON.stringify(value));
    else sessionStorage.removeItem(savedKey);
  } catch {
    // The active tab retains host access when browser storage is disabled.
  }
}

function showSession(value) {
  session = value;
  ended = false;
  storeSession(value);
  byId("host-heading").textContent = title.value.trim();
  joinLink.textContent = value.joinUrl;
  joinLink.href = value.joinUrl;
  byId("qr").src = sessionUrl(value.sessionId, "/qr.png");
  byId("share-status").textContent = "";
  view.reset("Waiting for captions…");
  hostStatus.textContent = "Microphone disconnected";
  stopPreview?.();
  stopPreview = subscribe(value.sessionId, view, state => {
    const label = { live: "Live", reconnecting: "Reconnecting…", ended: "Event ended" }[state];
    if (previewStatus.textContent !== label) previewStatus.textContent = label;
    if (state === "ended") {
      ended = true;
      hostStatus.textContent = "Event ended";
      refreshControls();
    }
  });
  refreshControls();
}

async function run(action) {
  if (busy) return;
  busy = true;
  setError();
  refreshControls();
  try {
    await action();
  } catch (error) {
    setError(error.message);
  } finally {
    busy = false;
    refreshControls();
  }
}

async function openCapture() {
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    throw new Error("Open the host page on HTTPS or localhost to use your microphone.");
  }
  if (!window.AudioContext || !window.AudioWorkletNode) {
    throw new Error("This browser does not support microphone capture for CaptionBridge.");
  }

  const context = new AudioContext();
  let media;
  let source;
  let processor;
  let muted;
  let acknowledgeFlush;

  try {
    const running = context.resume();
    // Permission can take time. Keep a handler on the resume promise while waiting.
    running.catch(() => {});
    media = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    await running;
    await context.audioWorklet.addModule(new URL("pcm-worklet.js", document.baseURI));
    source = context.createMediaStreamSource(media);
    processor = new AudioWorkletNode(context, "captionbridge-pcm");
    muted = context.createGain();
    muted.gain.value = 0;
    processor.port.onmessage = event => {
      const { pcm, level, flushed } = event.data;
      if (flushed) acknowledgeFlush?.();
      if (!pcm) return;
      meter.value = level;
      const socket = connection;
      if (socket?.readyState === WebSocket.OPEN && socket.audioReady) {
        if (socket.bufferedAmount > 256_000) {
          socket.close(4000, "Audio upload stalled");
          setError("Audio upload stalled. Reconnect the microphone to resume.");
          return;
        }
        socket.send(pcm);
      }
    };
    source.connect(processor).connect(muted).connect(context.destination);
  } catch (error) {
    media?.getTracks().forEach(track => track.stop());
    await context.close().catch(() => {});
    if (error.name === "NotAllowedError") {
      throw new Error("Microphone permission was denied. Allow microphone access and try again.");
    }
    if (error.name === "NotFoundError" || error.name === "NotReadableError") {
      throw new Error("Could not open a microphone. Check that it is connected and available.");
    }
    throw error;
  }

  return {
    async close(flush) {
      if (flush) {
        await new Promise(resolve => {
          const timeout = setTimeout(resolve, 150);
          acknowledgeFlush = () => {
            clearTimeout(timeout);
            resolve();
          };
          processor.port.postMessage("flush");
        });
      }
      source.disconnect();
      processor.disconnect();
      muted.disconnect();
      processor.port.close();
      media.getTracks().forEach(track => track.stop());
      meter.value = 0;
      await context.close();
    },
  };
}

async function stopAudio(flush = false) {
  const activeCapture = capture;
  capture = undefined;
  try {
    await activeCapture?.close(flush);
  } finally {
    const socket = connection;
    connection = undefined;
    if (socket && socket.readyState < WebSocket.CLOSING) {
      if (flush) {
        await new Promise(resolve => {
          const timeout = setTimeout(resolve, 1800);
          socket.addEventListener("close", () => {
            clearTimeout(timeout);
            resolve();
          }, { once: true });
          socket.close(1000);
        });
      } else {
        socket.close(1000);
      }
    }
  }
}

function connectAudio() {
  const url = new URL(`ws/host/${session.sessionId}`, document.baseURI);
  url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("token", session.hostToken);
  hostStatus.textContent = "Connecting microphone…";
  const socket = new WebSocket(url);
  connection = socket;

  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("The microphone connection timed out.")), 12_000);
    socket.addEventListener("message", event => {
      const message = JSON.parse(event.data);
      if (message.type !== "status") return;
      if (message.status === "ready") {
        clearTimeout(timeout);
        socket.audioReady = true;
        hostStatus.textContent = "Microphone connected";
        resolve();
      } else if (message.status === "error") {
        clearTimeout(timeout);
        const issue = message.message || "The microphone connection failed.";
        if (socket.audioReady) setError(issue);
        else reject(new Error(issue));
      }
    });
    socket.addEventListener("close", event => {
      clearTimeout(timeout);
      if (!socket.audioReady) reject(new Error("Could not connect the microphone to the session."));
      if (connection === socket) {
        const finished = ended || event.reason === "Event ended";
        if (finished) ended = true;
        hostStatus.textContent = finished ? "Event ended" : "Microphone disconnected";
        if (!finished && !busy && errorBox.hidden) setError("Microphone disconnected. Reconnect to continue.");
        stopAudio().catch(error => setError(error.message));
        refreshControls();
      }
    });
  });
}

async function startAudio(create) {
  try {
    capture = await openCapture();
    if (create) {
      const result = await request(new URL("api/sessions", document.baseURI), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: title.value.trim() }),
      });
      showSession(await result.json());
    }
    await connectAudio();
  } catch (error) {
    await stopAudio().catch(() => {});
    if (session && !ended) hostStatus.textContent = "Microphone disconnected";
    throw error;
  }
}

function hostHeaders() {
  return { "X-Host-Token": session.hostToken };
}

byId("session-form").addEventListener("submit", event => {
  event.preventDefault();
  if (!title.value.trim()) {
    setError("Enter a session title.");
    title.focus();
    return;
  }
  run(() => startAudio(true));
});

resume.addEventListener("click", () => run(() => startAudio(false)));

end.addEventListener("click", () => run(async () => {
  await stopAudio(true);
  hostStatus.textContent = "Ending session…";
  try {
    await request(sessionUrl(session.sessionId, "/end"), { method: "POST", headers: hostHeaders() });
    ended = true;
    hostStatus.textContent = "Event ended";
  } catch (error) {
    hostStatus.textContent = "Microphone disconnected";
    throw error;
  }
}));

byId("copy").addEventListener("click", async () => {
  const status = byId("share-status");
  try {
    await navigator.clipboard.writeText(session.joinUrl);
    status.textContent = "Link copied.";
  } catch {
    status.textContent = "Select the link above to copy it.";
  }
});

for (const button of document.querySelectorAll("[data-export]")) {
  button.addEventListener("click", () => run(async () => {
    const format = button.dataset.export;
    const url = sessionUrl(session.sessionId, "/export");
    url.searchParams.set("format", format);
    const result = await request(url, { headers: hostHeaders() });
    const objectUrl = URL.createObjectURL(await result.blob());
    const download = document.createElement("a");
    download.href = objectUrl;
    download.download = `captionbridge-${session.sessionId}.${format}`;
    document.body.append(download);
    download.click();
    download.remove();
    setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
  }));
}

byId("delete").addEventListener("click", () => {
  if (!window.confirm("Permanently delete this session and its transcript?")) return;
  run(async () => {
    await request(sessionUrl(session.sessionId), { method: "DELETE", headers: hostHeaders() });
    stopPreview?.();
    session = undefined;
    ended = false;
    storeSession(null);
    previewStatus.textContent = "";
    view.reset("Start a session to see captions here.");
    title.value = "";
    byId("host-heading").textContent = "Host a caption session";
  });
});

async function restoreSession() {
  let saved;
  try {
    saved = JSON.parse(sessionStorage.getItem(savedKey));
  } catch {
    return;
  }
  if (!saved?.sessionId || !saved?.hostToken || !saved?.joinUrl) return;
  busy = true;
  refreshControls();
  try {
    const result = await fetch(sessionUrl(saved.sessionId));
    if (result.status === 404) {
      storeSession(null);
      return;
    }
    if (!result.ok) throw new Error("Could not restore this session. Reload to try again.");
    const info = await result.json();
    title.value = info.title;
    showSession(saved);
    if (info.status === "ENDED") {
      ended = true;
      hostStatus.textContent = "Event ended";
    }
  } catch (error) {
    setError(error.message);
  } finally {
    busy = false;
    refreshControls();
  }
}

window.addEventListener("pagehide", () => {
  stopPreview?.();
  stopAudio().catch(() => {});
});
window.addEventListener("pageshow", event => {
  if (event.persisted) location.reload();
});
restoreSession();
