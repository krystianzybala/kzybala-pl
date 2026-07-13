// Lab registry — renders and filters the list of published labs from
// assets/data/labs-index.json. Mounts onto a container with [data-lab-registry]
// and, optionally, filter controls with [data-lab-filter="topic|difficulty|status"].
import { loadLabsIndex } from "./metadata.js";

function labCard(lab) {
  const a = document.createElement("a");
  a.className = "card";
  a.href = `/lab/${lab.id}/`;
  a.innerHTML = `
    <p class="meta">${lab.difficulty} · ${lab.durationMinutes} min</p>
    <h3>${lab.title}</h3>
    <div class="tags">${lab.topics.map(t => `<span class="tag">${t}</span>`).join("")}</div>
  `;
  return a;
}

function applyFilters(labs, filters) {
  return labs.filter(lab =>
    (!filters.topic || lab.topics.includes(filters.topic)) &&
    (!filters.difficulty || lab.difficulty === filters.difficulty) &&
    (!filters.status || lab.status === filters.status)
  );
}

export async function mountRegistry(root) {
  const list = root.querySelector("[data-lab-registry]");
  if (!list) return;

  const labs = await loadLabsIndex();
  const filterInputs = [...root.querySelectorAll("[data-lab-filter]")];
  const empty = root.querySelector("[data-lab-registry-empty]");
  const count = root.querySelector("[data-lab-filter-count]");

  // Filters marked [data-lab-filter-dynamic] get their <option>s populated
  // from the labs actually present, instead of a hand-maintained static list.
  for (const input of filterInputs) {
    if (!input.hasAttribute("data-lab-filter-dynamic")) continue;
    const field = input.dataset.labFilter;
    const values = [...new Set(labs.flatMap(lab => [].concat(lab[field] ?? [])))].sort();
    for (const value of values) {
      const option = document.createElement("option");
      option.value = value;
      option.textContent = value;
      input.append(option);
    }
  }

  const render = () => {
    const filters = Object.fromEntries(filterInputs.map(input => [input.dataset.labFilter, input.value || null]));
    const filtered = applyFilters(labs, filters);
    list.replaceChildren(...filtered.map(labCard));
    if (empty) empty.hidden = filtered.length > 0;
    if (count) count.textContent = `Showing ${filtered.length} of ${labs.length} laboratories`;
  };

  filterInputs.forEach(input => input.addEventListener("change", render));
  render();
}
