"use strict";

// Dashboard peer management. Talks to the same /api/peers endpoints as any API client, using the
// session cookie and the CSRF token from the page's <meta> tags. All server data is rendered with
// textContent, never innerHTML.
(function () {
    const REFRESH_MS = 15000;
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

    const el = (id) => document.getElementById(id);
    const devicesStatus = el("devicesStatus");
    const devicesTable = el("devicesTable");
    const devicesBody = el("devicesBody");
    const onlineStatus = el("onlineStatus");
    const onlineList = el("onlineList");
    if (!devicesBody || !onlineList) {
        return;
    }

    class ApiError extends Error {
        constructor(status, problem) {
            super(problem?.detail || "Request failed");
            this.status = status;
            this.problem = problem;
        }
    }

    async function api(method, url, body) {
        const headers = { Accept: "application/json" };
        if (method !== "GET" && csrfToken && csrfHeader) {
            headers[csrfHeader] = csrfToken;
        }
        if (body !== undefined) {
            headers["Content-Type"] = "application/json";
        }
        const response = await fetch(url, {
            method,
            headers,
            credentials: "same-origin",
            body: body === undefined ? undefined : JSON.stringify(body),
        });
        if (response.status === 401) {
            window.location.assign("/login"); // session expired
            throw new ApiError(401, null);
        }
        const text = await response.text();
        const data = text ? JSON.parse(text) : null;
        if (!response.ok) {
            throw new ApiError(response.status, data);
        }
        return data;
    }

    function node(tag, className, text) {
        const element = document.createElement(tag);
        if (className) {
            element.className = className;
        }
        if (text !== undefined && text !== null) {
            element.textContent = text;
        }
        return element;
    }

    function relativeTime(iso) {
        if (!iso) {
            return "never";
        }
        const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
        if (seconds < 60) {
            return seconds + " s ago";
        }
        if (seconds < 3600) {
            return Math.round(seconds / 60) + " min ago";
        }
        return new Date(iso).toLocaleString();
    }

    function statusBadge(status) {
        const online = status === "ONLINE";
        return node("span", "badge " + (online ? "text-bg-success" : "text-bg-secondary"), online ? "Online" : "Offline");
    }

    function showStatus(target, message, isError) {
        target.textContent = message;
        target.className = isError ? "alert alert-danger small py-2" : "text-body-secondary small";
        target.classList.remove("d-none");
    }

    // --- Your devices ------------------------------------------------------------------

    async function loadDevices() {
        try {
            const peers = await api("GET", "/api/peers/mine");
            devicesBody.replaceChildren(...peers.map(deviceRow));
            if (peers.length === 0) {
                devicesTable.classList.add("d-none");
                showStatus(devicesStatus, "No devices yet. Use “Add a device” to register one.");
            } else {
                devicesStatus.classList.add("d-none");
                devicesTable.classList.remove("d-none");
            }
        } catch (error) {
            if (error.status !== 401) {
                showStatus(devicesStatus, "Your devices could not be loaded. Please try again shortly.", true);
            }
        }
    }

    function deviceRow(peer) {
        const row = node("tr");

        const name = node("td");
        name.append(node("div", "fw-medium", peer.deviceName), node("div", "small text-body-secondary",
            "Port " + peer.listeningPort));
        const status = node("td");
        status.append(statusBadge(peer.status));
        if (peer.status !== "ONLINE" && !peer.lastHeartbeatAt && peer.agentCredentialActive) {
            status.append(node("div", "small text-body-secondary", "Waiting for first heartbeat"));
        }
        const heartbeat = node("td", "small", relativeTime(peer.lastHeartbeatAt));
        if (peer.lastHeartbeatAt) {
            heartbeat.title = new Date(peer.lastHeartbeatAt).toLocaleString();
        }
        const address = node("td", "small font-monospace", peer.hostAddress || "—");
        const credential = node("td", "small", peer.agentCredentialActive ? "Active" : "Revoked");

        const actions = node("td", "text-end text-nowrap");
        const rotate = node("button", "btn btn-outline-secondary btn-sm me-1", "New credential");
        rotate.type = "button";
        rotate.addEventListener("click", () => rotateCredential(peer));
        actions.append(rotate);
        if (peer.agentCredentialActive) {
            const revoke = node("button", "btn btn-outline-danger btn-sm", "Revoke");
            revoke.type = "button";
            revoke.addEventListener("click", () => revokeCredential(peer));
            actions.append(revoke);
        }

        row.append(name, status, heartbeat, address, credential, actions);
        return row;
    }

    async function rotateCredential(peer) {
        if (!window.confirm("Issue a new agent credential for “" + peer.deviceName + "”? The current one stops working immediately.")) {
            return;
        }
        try {
            const credential = await api("POST", "/api/peers/" + peer.id + "/credential");
            showCredential(peer.deviceName, credential);
            await loadDevices();
        } catch (error) {
            if (error.status !== 401) {
                showStatus(devicesStatus, "The credential could not be replaced. Please try again.", true);
            }
        }
    }

    async function revokeCredential(peer) {
        if (!window.confirm("Revoke the agent credential for “" + peer.deviceName + "”? Its agent will be disconnected.")) {
            return;
        }
        try {
            await api("DELETE", "/api/peers/" + peer.id + "/credential");
            await loadDevices();
        } catch (error) {
            if (error.status !== 401) {
                showStatus(devicesStatus, "The credential could not be revoked. Please try again.", true);
            }
        }
    }

    // --- Registration and the one-time credential ------------------------------------------

    const form = el("registerDeviceForm");
    const registerError = el("registerError");

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        form.querySelectorAll(".is-invalid").forEach((input) => input.classList.remove("is-invalid"));
        registerError.classList.add("d-none");
        const submit = form.querySelector("button[type=submit]");
        submit.disabled = true;
        try {
            const result = await api("POST", "/api/peers", {
                deviceName: form.deviceName.value,
                listeningPort: Number.parseInt(form.listeningPort.value, 10),
            });
            form.reset();
            form.listeningPort.value = 9090;
            showCredential(result.peer.deviceName, result.agentCredential);
            await loadDevices();
        } catch (error) {
            showFormErrors(error);
        } finally {
            submit.disabled = false;
        }
    });

    function showFormErrors(error) {
        if (error.status === 401) {
            return;
        }
        const fieldErrors = error.problem?.errors || [];
        fieldErrors.forEach((fieldError) => {
            const input = form.elements.namedItem(fieldError.field);
            const feedback = form.querySelector('[data-error-for="' + fieldError.field + '"]');
            if (input && feedback) {
                input.classList.add("is-invalid");
                feedback.textContent = fieldError.message;
            }
        });
        if (fieldErrors.length === 0) {
            // Only messages written for users (problem "detail") are shown; anything else is generic.
            registerError.textContent = error.status === 400 || error.status === 409
                ? error.message : "The device could not be registered. Please try again.";
            registerError.classList.remove("d-none");
        }
    }

    function showCredential(deviceName, credential) {
        const panel = el("credentialPanel");
        panel.querySelector('[data-field="deviceName"]').textContent = deviceName;
        el("credentialValue").value = credential.credential;
        el("agentCommand").textContent = [
            "export PEERLINK_PEER_ID=" + credential.peerId,
            "export PEERLINK_AGENT_CREDENTIAL='" + credential.credential + "'",
            "java -jar peer-agent.jar --server-url=" + window.location.origin,
        ].join("\n");
        panel.classList.remove("d-none");
        panel.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }

    el("copyCredential").addEventListener("click", async () => {
        const input = el("credentialValue");
        try {
            await navigator.clipboard.writeText(input.value);
            el("copyCredential").textContent = "Copied";
        } catch (e) {
            input.select(); // clipboard API unavailable: let the user copy manually
        }
    });

    el("dismissCredential").addEventListener("click", () => {
        el("credentialValue").value = "";
        el("agentCommand").textContent = "";
        el("copyCredential").textContent = "Copy";
        el("credentialPanel").classList.add("d-none");
    });

    // --- Online peers ------------------------------------------------------------------

    async function loadOnlinePeers() {
        try {
            const page = await api("GET", "/api/peers/online?size=50");
            onlineList.replaceChildren(...page.content.map(onlineItem));
            if (page.content.length === 0) {
                onlineList.classList.add("d-none");
                showStatus(onlineStatus, "No other peers are online right now.");
            } else {
                onlineStatus.classList.add("d-none");
                onlineList.classList.remove("d-none");
            }
        } catch (error) {
            if (error.status !== 401) {
                showStatus(onlineStatus, "Online peers could not be loaded. Please try again shortly.", true);
            }
        }
    }

    function onlineItem(peer) {
        const item = node("li", "list-group-item d-flex justify-content-between align-items-center px-0");
        const who = node("div");
        who.append(node("div", "fw-medium", peer.deviceName),
            node("div", "small text-body-secondary", (peer.ownerDisplayName || peer.ownerUsername) + " · @" + peer.ownerUsername));
        const seen = node("span", "small text-body-secondary", "seen " + relativeTime(peer.lastHeartbeatAt));
        item.append(who, seen);
        return item;
    }

    el("refreshOnline").addEventListener("click", loadOnlinePeers);

    loadDevices();
    loadOnlinePeers();
    setInterval(() => {
        if (!document.hidden) {
            loadDevices();
            loadOnlinePeers();
        }
    }, REFRESH_MS);
})();
