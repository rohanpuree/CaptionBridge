import { captionView, request, sessionUrl, subscribe } from "./captions.js";

const view = captionView("caption");
const title = document.querySelector("#event-title");
const status = document.querySelector("#caption-status");
const theme = document.querySelector("#theme");
const smaller = document.querySelector("#smaller");
const larger = document.querySelector("#larger");
const root = document.documentElement;
const sizes = ["1.25rem", "1.5rem", "1.85rem", "2.25rem", "2.75rem"];
let size = 1;

function preference(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    localStorage.setItem(key, value);
  } catch {
    // Display settings still work with browser storage disabled.
  }
  return null;
}

function setTheme(dark) {
  root.dataset.theme = dark ? "dark" : "light";
  theme.setAttribute("aria-pressed", String(dark));
  preference("captionbridge:theme", root.dataset.theme);
}

function setSize(index) {
  size = Math.min(sizes.length - 1, Math.max(0, index));
  root.style.setProperty("--caption-size", sizes[size]);
  smaller.disabled = size === 0;
  larger.disabled = size === sizes.length - 1;
  preference("captionbridge:caption-size", String(size));
  view.scrollToLatest();
}

setTheme((preference("captionbridge:theme") ||
  (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light")) === "dark");
const storedSize = preference("captionbridge:caption-size");
if (storedSize !== null && /^\d$/.test(storedSize)) setSize(Number(storedSize));
theme.addEventListener("click", () => setTheme(root.dataset.theme !== "dark"));
smaller.addEventListener("click", () => setSize(size - 1));
larger.addEventListener("click", () => setSize(size + 1));

let stop;
window.addEventListener("pagehide", () => stop?.());
window.addEventListener("pageshow", event => {
  if (event.persisted) location.reload();
});

const id = new URLSearchParams(location.search).get("session");
if (!id || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(id)) {
  status.textContent = "Open the join link or scan the QR code from your host.";
  status.classList.add("error");
  document.querySelector("#caption-empty").hidden = true;
} else {
  openSession();
}

async function openSession() {
  try {
    const result = await request(sessionUrl(id));
    const session = await result.json();
    title.textContent = session.title;
    document.title = `${session.title} · CaptionBridge`;
    stop = subscribe(id, view, state => {
      const message = {
        live: "Live captions",
        reconnecting: "Reconnecting…",
        ended: "Event ended",
      }[state];
      if (status.textContent !== message) status.textContent = message;
    });
  } catch (error) {
    status.textContent = `Could not open this session. ${error.message}`;
    status.classList.add("error");
    document.querySelector("#caption-empty").hidden = true;
  }
}
