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

    let myDevices = [];

    async function loadDevices() {
        try {
            const peers = await api("GET", "/api/peers/mine");
            myDevices = peers;
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

    // --- Shared files ---------------------------------------------------------------------

    function formatBytes(bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        const units = ["KiB", "MiB", "GiB", "TiB"];
        let value = bytes / 1024;
        let unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return value.toFixed(value < 10 ? 1 : 0) + " " + units[unit];
    }

    function onlineBadge(online) {
        return node("span", "badge ms-1 " + (online ? "text-bg-success" : "text-bg-secondary"), online ? "online" : "offline");
    }

    async function loadMyShares() {
        const status = el("mySharesStatus");
        const list = el("mySharesList");
        try {
            const page = await api("GET", "/api/files/mine?size=50");
            list.replaceChildren(...page.content.map(myShareItem));
            if (page.content.length === 0) {
                list.classList.add("d-none");
                showStatus(status, "You are not sharing any files yet.");
            } else {
                status.classList.add("d-none");
                list.classList.remove("d-none");
            }
        } catch (error) {
            if (error.status !== 401) {
                showStatus(status, "Your shared files could not be loaded.", true);
            }
        }
    }

    function myShareItem(file) {
        const item = node("li", "list-group-item px-0");
        const header = node("div", "d-flex flex-wrap justify-content-between align-items-center gap-2");
        const info = node("div");
        const device = node("div", "small text-body-secondary", formatBytes(file.fileSizeBytes) + " · on " + file.sourceDeviceName);
        device.append(onlineBadge(file.sourcePeerOnline));
        info.append(node("div", "fw-medium text-break", file.fileName), device);

        const actions = node("div", "text-nowrap");
        const manage = node("button", "btn btn-outline-primary btn-sm me-1", "Manage access");
        manage.type = "button";
        const stop = node("button", "btn btn-outline-danger btn-sm", "Stop sharing");
        stop.type = "button";
        actions.append(manage, stop);
        header.append(info, actions);

        const access = node("div", "mt-2 d-none");
        item.append(header, access);

        manage.addEventListener("click", async () => {
            access.classList.toggle("d-none");
            if (!access.classList.contains("d-none")) {
                await renderAccess(file, access);
            }
        });
        stop.addEventListener("click", async () => {
            if (!window.confirm("Stop sharing \u201c" + file.fileName + "\u201d? Nobody will be able to fetch it any more.")) {
                return;
            }
            try {
                await api("DELETE", "/api/files/" + file.id);
                await Promise.all([loadMyShares(), loadTransfers()]);
            } catch (error) {
                if (error.status !== 401) {
                    window.alert("The file could not be unshared. Please try again.");
                }
            }
        });
        return item;
    }

    async function renderAccess(file, container) {
        container.replaceChildren(node("div", "small text-body-secondary", "Loading access…"));
        let permissions;
        try {
            permissions = await api("GET", "/api/files/" + file.id + "/permissions");
        } catch (error) {
            container.replaceChildren(node("div", "alert alert-danger small py-2", "Access could not be loaded."));
            return;
        }
        const list = node("ul", "list-unstyled small mb-2");
        permissions.filter((permission) => permission.active).forEach((permission) => {
            const entry = node("li", "d-flex align-items-center gap-2 mb-1");
            const revoke = node("button", "btn btn-link btn-sm p-0 text-danger", "revoke");
            revoke.type = "button";
            revoke.addEventListener("click", async () => {
                try {
                    await api("DELETE", "/api/files/" + file.id + "/permissions/" + permission.id);
                    await renderAccess(file, container);
                } catch (error) {
                    window.alert("Access could not be revoked. Please try again.");
                }
            });
            entry.append(node("span", null, "@" + permission.permittedUsername), revoke);
            list.append(entry);
        });
        if (list.childElementCount === 0) {
            list.append(node("li", "text-body-secondary", "Nobody else can fetch this file yet."));
        }

        const form = node("form", "d-flex gap-2");
        form.noValidate = true;
        const input = node("input", "form-control form-control-sm");
        input.placeholder = "username";
        input.setAttribute("aria-label", "Username to share with");
        input.maxLength = 32;
        const submit = node("button", "btn btn-primary btn-sm", "Share");
        submit.type = "submit";
        form.append(input, submit);
        const feedback = node("div", "small text-danger mt-1");
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            feedback.textContent = "";
            try {
                await api("POST", "/api/files/" + file.id + "/permissions", { username: input.value.trim() });
                await renderAccess(file, container);
            } catch (error) {
                if (error.status !== 401) {
                    feedback.textContent = error.status === 400 || error.status === 404
                        ? (error.problem?.errors?.[0]?.message || error.message) : "Sharing failed. Please try again.";
                }
            }
        });
        container.replaceChildren(list, form, feedback);
    }

    async function loadSharedWithMe() {
        const status = el("sharedWithMeStatus");
        const list = el("sharedWithMeList");
        try {
            const page = await api("GET", "/api/files/shared-with-me?size=50");
            list.replaceChildren(...page.content.map(sharedWithMeItem));
            if (page.content.length === 0) {
                list.classList.add("d-none");
                showStatus(status, "Nobody has shared a file with you yet.");
            } else {
                status.classList.add("d-none");
                list.classList.remove("d-none");
            }
        } catch (error) {
            if (error.status !== 401) {
                showStatus(status, "Files shared with you could not be loaded.", true);
            }
        }
    }

    function sharedWithMeItem(file) {
        const item = node("li", "list-group-item px-0");
        const info = node("div", "mb-2");
        const meta = node("div", "small text-body-secondary",
            formatBytes(file.fileSizeBytes) + " · from @" + file.ownerUsername + " (" + file.sourceDeviceName + ")");
        meta.append(onlineBadge(file.sourcePeerOnline));
        info.append(node("div", "fw-medium text-break", file.fileName), meta);

        const form = node("form", "d-flex gap-2");
        const select = node("select", "form-select form-select-sm");
        select.setAttribute("aria-label", "Device to receive " + file.fileName);
        myDevices.forEach((device) => {
            const option = node("option", null, device.deviceName);
            option.value = device.id;
            select.append(option);
        });
        const submit = node("button", "btn btn-primary btn-sm text-nowrap", "Send to device");
        submit.type = "submit";
        submit.disabled = myDevices.length === 0 || !file.sourcePeerOnline;
        form.append(select, submit);
        const feedback = node("div", "small mt-1");
        if (myDevices.length === 0) {
            feedback.textContent = "Register a device first.";
        } else if (!file.sourcePeerOnline) {
            feedback.textContent = "The owner's device is offline.";
        }
        form.addEventListener("submit", async (event) => {
            event.preventDefault();
            submit.disabled = true;
            feedback.className = "small mt-1";
            try {
                await api("POST", "/api/transfers", { sharedFileId: file.id, receiverPeerId: select.value });
                feedback.textContent = "Requested. Your device's agent will fetch it shortly.";
                feedback.classList.add("text-success");
                await loadTransfers();
            } catch (error) {
                if (error.status !== 401) {
                    feedback.textContent = error.status === 409 || error.status === 404 || error.status === 400
                        ? error.message : "The transfer could not be requested. Please try again.";
                    feedback.classList.add("text-danger");
                }
            } finally {
                submit.disabled = false;
            }
        });
        item.append(info, form, feedback);
        return item;
    }

    // --- Transfers ------------------------------------------------------------------------

    const STATUS_LABELS = {
        REQUESTED: ["Waiting for the device", "text-bg-secondary"],
        AUTHORIZED: ["Accepted", "text-bg-info"],
        IN_PROGRESS: ["Transferring", "text-bg-primary"],
        COMPLETED: ["Completed", "text-bg-success"],
        REJECTED: ["Declined", "text-bg-warning"],
        FAILED: ["Failed", "text-bg-danger"],
        CANCELLED: ["Cancelled", "text-bg-secondary"],
    };

    const ERROR_TEXT = {
        PERMISSION_DENIED: "Access was revoked or is no longer valid.",
        PEER_UNREACHABLE: "The sending device could not be reached (firewall, network or offline).",
        PEER_BUSY: "The sending device was busy with other transfers. Try again.",
        CONNECTION_LOST: "The connection between the devices was lost.",
        FILE_UNAVAILABLE: "The file is no longer available on the sending device.",
        FILE_CHANGED: "The file changed on the sending device after it was shared. Share it again.",
        FILE_TOO_LARGE: "The file is larger than the receiving device accepts.",
        INSUFFICIENT_STORAGE: "Not enough free space on the receiving device.",
        CHECKSUM_MISMATCH: "The received data did not match the file's checksum and was discarded.",
        METADATA_MISMATCH: "The sending device offered different file details than expected.",
        STORAGE_ERROR: "The receiving device could not save the file.",
        PROTOCOL_ERROR: "The devices could not agree on the transfer.",
        TIMEOUT: "The transfer stopped responding.",
        AGENT_SHUTDOWN: "A device's agent was stopped during the transfer.",
        REQUEST_EXPIRED: "The receiving device did not pick up the request in time.",
        SERVER_UNAVAILABLE: "The sending device could not confirm the transfer with the server.",
        TOKEN_REJECTED: "The connection was not authorized.",
    };

    let activeTransfers = false;

    async function loadTransfers() {
        const status = el("transfersStatus");
        const table = el("transfersTable");
        const body = el("transfersBody");
        try {
            const page = await api("GET", "/api/transfers?size=20");
            body.replaceChildren(...page.content.map(transferRow));
            activeTransfers = page.content.some((transfer) => ["REQUESTED", "AUTHORIZED", "IN_PROGRESS"].includes(transfer.status));
            if (page.content.length === 0) {
                table.classList.add("d-none");
                showStatus(status, "No transfers yet.");
            } else {
                status.classList.add("d-none");
                table.classList.remove("d-none");
            }
        } catch (error) {
            if (error.status !== 401) {
                showStatus(status, "Transfers could not be loaded. Please try again shortly.", true);
            }
        }
    }

    function transferRow(transfer) {
        const row = node("tr");
        const file = node("td");
        file.append(node("div", "fw-medium text-break", transfer.fileName),
            node("div", "small text-body-secondary", formatBytes(transfer.totalBytes) + " · " + relativeTime(transfer.createdAt)));
        const route = node("td", "small", transfer.senderDeviceName + " → " + transfer.receiverDeviceName);

        const [label, style] = STATUS_LABELS[transfer.status] || [transfer.status, "text-bg-secondary"];
        const statusCell = node("td");
        statusCell.append(node("span", "badge " + style, label));
        if (transfer.errorCode) {
            statusCell.append(node("div", "small text-body-secondary mt-1", ERROR_TEXT[transfer.errorCode] || transfer.errorCode));
        } else if (transfer.status === "COMPLETED" && transfer.checksumVerified) {
            statusCell.append(node("div", "small text-body-secondary mt-1", "SHA-256 verified"));
        }

        const progressCell = node("td");
        const percent = transfer.totalBytes === 0
            ? (transfer.status === "COMPLETED" ? 100 : 0)
            : Math.floor((transfer.transferredBytes / transfer.totalBytes) * 100);
        const bar = node("div", "progress transfer-progress");
        bar.setAttribute("role", "progressbar");
        bar.setAttribute("aria-label", "Progress of " + transfer.fileName);
        bar.setAttribute("aria-valuemin", "0");
        bar.setAttribute("aria-valuemax", "100");
        bar.setAttribute("aria-valuenow", String(percent));
        const fill = node("div", "progress-bar" + (transfer.status === "IN_PROGRESS" ? " progress-bar-striped progress-bar-animated" : ""));
        fill.style.width = percent + "%";
        bar.append(fill);
        progressCell.append(bar, node("div", "small text-body-secondary mt-1",
            percent + "% · " + formatBytes(transfer.transferredBytes) + " of " + formatBytes(transfer.totalBytes)));

        const actions = node("td", "text-end");
        if (["REQUESTED", "AUTHORIZED", "IN_PROGRESS"].includes(transfer.status)) {
            const cancel = node("button", "btn btn-outline-danger btn-sm", "Cancel");
            cancel.type = "button";
            cancel.addEventListener("click", async () => {
                try {
                    await api("POST", "/api/transfers/" + transfer.id + "/cancel");
                } catch (error) {
                    if (error.status !== 401 && error.status !== 409) {
                        window.alert("The transfer could not be cancelled. Please try again.");
                    }
                }
                await loadTransfers();
            });
            actions.append(cancel);
        }
        row.append(file, route, statusCell, progressCell, actions);
        return row;
    }

    el("refreshTransfers").addEventListener("click", loadTransfers);

    loadDevices().then(loadSharedWithMe);
    loadOnlinePeers();
    loadMyShares();
    loadTransfers();
    setInterval(() => {
        if (!document.hidden) {
            loadDevices();
            loadOnlinePeers();
            loadMyShares();
            loadSharedWithMe();
        }
    }, REFRESH_MS);
    // Running transfers refresh every few seconds; otherwise at the normal pace.
    let ticks = 0;
    setInterval(() => {
        ticks++;
        if (!document.hidden && (activeTransfers || ticks % 5 === 0)) {
            loadTransfers();
        }
    }, 3000);
})();
