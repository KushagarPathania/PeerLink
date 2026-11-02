"use strict";

// Password visibility toggles: <button data-password-toggle aria-controls="inputId">.
document.querySelectorAll("[data-password-toggle]").forEach((button) => {
    const input = document.getElementById(button.getAttribute("aria-controls"));
    if (!input) {
        return;
    }
    button.addEventListener("click", () => {
        const reveal = input.type === "password";
        input.type = reveal ? "text" : "password";
        button.textContent = reveal ? "Hide" : "Show";
        button.setAttribute("aria-pressed", String(reveal));
        button.setAttribute("aria-label", reveal ? "Hide password" : "Show password");
    });
});

// Coordination server status card on the landing page.
(function () {
    const serverBadge = document.getElementById("server-status");
    const dbStatus = document.getElementById("db-status");
    if (!serverBadge || !dbStatus) {
        return;
    }

    function render(status, database) {
        const up = status === "UP";
        serverBadge.textContent = up ? "Online" : "Degraded";
        serverBadge.className = "badge status-badge " + (up ? "text-bg-success" : "text-bg-warning");
        dbStatus.textContent = database;
    }

    // 503 still carries a health body, so parse the JSON regardless of status code.
    fetch("/api/health", { headers: { Accept: "application/json" } })
        .then((response) => response.json())
        .then((health) => render(health.status, health.database))
        .catch(() => {
            serverBadge.textContent = "Unreachable";
            serverBadge.className = "badge status-badge text-bg-danger";
            dbStatus.textContent = "unknown";
        });
})();
