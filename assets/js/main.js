
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

  // Hero runtime animation
  const pipelineNodes = [...document.querySelectorAll(".runtime-panel .pipeline-node")];
  const readout = document.querySelector("[data-latency]");
  if (pipelineNodes.length) {
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

  // Lab tabs
  const tabButtons = [...document.querySelectorAll("[data-lab-target]")];
  tabButtons.forEach(button => button.addEventListener("click", () => {
    const target = button.dataset.labTarget;
    tabButtons.forEach(b => b.classList.toggle("active", b === button));
    document.querySelectorAll(".lab-view").forEach(view => view.classList.toggle("active", view.id === target));
  }));

  // False sharing visualizer
  let writeSide = 0;
  const bus = document.querySelector(".coherence-bus");
  document.querySelector("[data-coherence-step]")?.addEventListener("click", () => {
    writeSide = 1 - writeSide;
    document.querySelectorAll(".cpu .cache-cell").forEach((cell, i) => {
      const cpu = cell.closest(".cpu").dataset.cpu;
      cell.classList.toggle("hot", Number(cpu) === writeSide && i === 3);
    });
    document.querySelectorAll("[data-state]").forEach(el => {
      el.textContent = Number(el.closest(".cpu").dataset.cpu) === writeSide ? "Modified" : "Invalid";
    });
    bus?.classList.remove("pulse");
    void bus?.offsetWidth;
    bus?.classList.add("pulse");
  });

  // Ring buffer
  const ringSlots = [...document.querySelectorAll(".ring .slot")];
  let head = 0, tail = 0, size = 0;
  const renderRing = () => {
    ringSlots.forEach((slot, i) => slot.classList.toggle("filled", i < size));
    document.querySelector("[data-head]")?.replaceChildren(document.createTextNode(String(head)));
    document.querySelector("[data-tail]")?.replaceChildren(document.createTextNode(String(tail)));
    document.querySelector("[data-size]")?.replaceChildren(document.createTextNode(String(size)));
  };
  document.querySelector("[data-produce]")?.addEventListener("click", () => {
    if (size < ringSlots.length) { head = (head + 1) % ringSlots.length; size++; renderRing(); }
  });
  document.querySelector("[data-consume]")?.addEventListener("click", () => {
    if (size > 0) { tail = (tail + 1) % ringSlots.length; size--; renderRing(); }
  });
  renderRing();

  // JIT pipeline
  const jitNodes = [...document.querySelectorAll(".jit-node")];
  let jitIndex = 0;
  document.querySelector("[data-jit-step]")?.addEventListener("click", () => {
    jitNodes.forEach((n, i) => n.classList.toggle("active", i === jitIndex));
    jitIndex = (jitIndex + 1) % jitNodes.length;
  });
})();
