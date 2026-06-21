const currentYear = new Date().getFullYear();
document.querySelectorAll("[data-year]").forEach(el => el.textContent = currentYear);

async function checkLatestVersion() {
  const versionBox = document.querySelector("[data-version-box]");
  if (!versionBox) return;

  try {
    const res = await fetch("/downloads/app-version.json", { cache: "no-store" });
    if (!res.ok) throw new Error("version file unavailable");
    const data = await res.json();

    versionBox.innerHTML = `
      <strong>Latest version:</strong> ${data.latestVersion}<br>
      <strong>Version code:</strong> ${data.versionCode}<br>
      <strong>Release notes:</strong> ${data.releaseNotes}
    `;

    const btn = document.querySelector("[data-apk-link]");
    if (btn && data.apkUrl) {
      btn.href = data.apkUrl;
    }
  } catch (e) {
    versionBox.innerHTML = "Latest version: v1.0";
  }
}

checkLatestVersion();
