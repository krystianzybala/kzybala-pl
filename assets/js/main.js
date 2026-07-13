
(() => {
  const root = document.documentElement;
  const savedTheme = localStorage.getItem("theme");
  const preferred = matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  root.dataset.theme = savedTheme || preferred;

  document.querySelector(".theme-toggle")?.addEventListener("click", () => {
    root.dataset.theme = root.dataset.theme === "light" ? "dark" : "light";
    localStorage.setItem("theme", root.dataset.theme);
  });

  const navToggle = document.querySelector(".nav-toggle");
  const navLinks = document.querySelector(".nav-links");
  navToggle?.addEventListener("click", () => {
    const open = navLinks.classList.toggle("open");
    navToggle.setAttribute("aria-expanded", String(open));
  });

  const path = location.pathname.replace(/\/index\.html$/, "/");
  document.querySelectorAll(".nav-links a").forEach(a => {
    const href = new URL(a.href).pathname.replace(/\/index\.html$/, "/");
    if (href === path || (href !== "/" && path.startsWith(href))) a.setAttribute("aria-current", "page");
  });

  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) if (entry.isIntersecting) entry.target.classList.add("visible");
  }, { threshold: .12 });
  document.querySelectorAll(".section, .signal-strip, .runtime-panel").forEach(el => {
    el.classList.add("reveal");
    observer.observe(el);
  });

  // Hero runtime animation — skipped entirely under reduced motion (docs/reduced-motion-policy.md),
  // since this is a JS setInterval loop, not CSS, and so isn't covered by the global CSS override.
  const pipelineNodes = [...document.querySelectorAll(".runtime-panel .pipeline-node")];
  const readout = document.querySelector("[data-latency]");
  if (pipelineNodes.length && !matchMedia("(prefers-reduced-motion: reduce)").matches) {
    let index = 0;
    setInterval(() => {
      pipelineNodes.forEach((n, i) => n.classList.toggle("active", i === index));
      if (readout) {
        const values = ["7.4", "5.9", "4.8", "3.6", "2.9"];
        readout.textContent = values[index % values.length];
      }
      index = (index + 1) % pipelineNodes.length;
    }, 1100);
  }
})();
