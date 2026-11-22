export function sessionUrl(id, suffix = "") {
  return new URL(`api/sessions/${encodeURIComponent(id)}${suffix}`, document.baseURI);
}

export async function request(url, options) {
  const result = await fetch(url, options);
  if (!result.ok) {
    const detail = await result.json().catch(() => null);
    throw new Error(detail?.detail || result.statusText || `Request failed (${result.status}).`);
  }
  return result;
}

export function captionView(prefix) {
  const scroll = document.getElementById(`${prefix}-scroll`);
  const finals = document.getElementById(`${prefix}-finals`);
  const empty = document.getElementById(`${prefix}-empty`);
  const partial = document.getElementById(`${prefix}-partial`);
  const jump = document.getElementById(`${prefix}-jump`);
  let following = true;

  function scrollToLatest() {
    if (following) scroll.scrollTop = scroll.scrollHeight;
  }

  scroll.addEventListener("scroll", () => {
    following = scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 32;
    jump.hidden = following;
  }, { passive: true });
  jump.addEventListener("click", () => {
    following = true;
    jump.hidden = true;
    scrollToLatest();
    scroll.focus({ preventScroll: true });
  });

  return {
    append(text, seq) {
      const line = document.createElement("p");
      line.textContent = text;
      line.dataset.seq = seq;
      finals.append(line);
      empty.hidden = true;
      partial.hidden = true;
      scrollToLatest();
    },
    partial(text) {
      partial.textContent = text;
      partial.hidden = !text;
      empty.hidden = Boolean(text) || finals.childElementCount > 0;
      scrollToLatest();
    },
    finish() {
      partial.hidden = true;
      if (finals.childElementCount === 0) {
        empty.textContent = "No captions were recorded.";
        empty.hidden = false;
      }
    },
    reset(message) {
      following = true;
      jump.hidden = true;
      finals.replaceChildren();
      partial.hidden = true;
      empty.textContent = message;
      empty.hidden = false;
    },
    scrollToLatest,
  };
}

export function subscribe(id, view, onState) {
  let cursor = 0;
  let source;
  let retryTimer;
  let retryCount = 0;
  let stopped = false;

  function stop() {
    stopped = true;
    clearTimeout(retryTimer);
    source?.close();
  }

  function reconnect(allowNative) {
    if (stopped) return;
    onState("reconnecting");
    if (retryTimer !== undefined) return;
    const delay = Math.min(25_000, (allowNative ? 4000 : 700) * 2 ** retryCount);
    retryCount = Math.min(8, retryCount + 1);
    retryTimer = setTimeout(() => {
      retryTimer = undefined;
      source?.close();
      connect();
    }, delay * (0.85 + Math.random() * 0.3));
  }

  function connect() {
    if (stopped) return;
    const url = sessionUrl(id, "/stream");
    url.searchParams.set("after", cursor);
    const current = new EventSource(url);
    source = current;
    const active = () => !stopped && current === source;

    function received() {
      clearTimeout(retryTimer);
      retryTimer = undefined;
      retryCount = 0;
    }

    current.addEventListener("caption", event => {
      if (!active()) return;
      const seq = Number(event.lastEventId);
      if (!Number.isSafeInteger(seq) || seq <= cursor) return;
      if (seq !== cursor + 1) {
        current.close();
        reconnect(false);
        return;
      }
      const line = JSON.parse(event.data);
      view.append(line.text, seq);
      cursor = seq;
      received();
      onState("live");
    });
    current.addEventListener("partial", event => {
      if (!active()) return;
      const line = JSON.parse(event.data);
      if (line.seq === cursor + 1) view.partial(line.text);
      received();
      onState("live");
    });
    current.addEventListener("status", event => {
      if (!active()) return;
      received();
      if (JSON.parse(event.data).status === "ENDED") {
        view.finish();
        onState("ended");
        stop();
      } else {
        onState("live");
      }
    });
    current.addEventListener("ping", () => {
      if (active()) {
        received();
        onState("live");
      }
    });
    current.onerror = () => {
      if (active()) reconnect(current.readyState !== EventSource.CLOSED);
    };
  }

  connect();
  return stop;
}
