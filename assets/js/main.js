
(() => {
  const root = document.documentElement;
  const saved = localStorage.getItem("theme");
  const prefersLight = window.matchMedia("(prefers-color-scheme: light)").matches;
  root.dataset.theme = saved || (prefersLight ? "light" : "dark");

  document.querySelector(".theme-toggle")?.addEventListener("click", () => {
    root.dataset.theme = root.dataset.theme === "light" ? "dark" : "light";
    localStorage.setItem("theme", root.dataset.theme);
  });

  const toggle = document.querySelector(".nav-toggle");
  const links = document.querySelector(".nav-links");
  toggle?.addEventListener("click", () => {
    const open = links.classList.toggle("open");
    toggle.setAttribute("aria-expanded", String(open));
  });

  const path = location.pathname.replace(/\/index\.html$/, "/");
  document.querySelectorAll(".nav-links a").forEach(a => {
    const href = new URL(a.href).pathname.replace(/\/index\.html$/, "/");
    if (href === path || (href !== "/" && path.startsWith(href))) {
      a.setAttribute("aria-current", "page");
    }
  });
})();
