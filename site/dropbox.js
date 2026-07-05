// Dropbox integration for loading GPX files directly from your Dropbox.
//
// This uses the OAuth 2.0 "PKCE" flow, which is designed for browser-only /
// static apps: there is NO client secret, so it is safe to publish the app key
// below in a public repo. Security comes from PKCE + the redirect URIs you
// register in the Dropbox App Console.
//
// SETUP: paste your app key from https://www.dropbox.com/developers/apps here.
const DROPBOX_APP_KEY = 'kkon8scyxqw70w9'

// OAuth scopes the app requests. These must also be enabled on the
// "Permissions" tab of your app in the Dropbox App Console.
const DROPBOX_SCOPES = 'files.metadata.read files.content.read';

// localStorage / sessionStorage keys
const LS_REFRESH_TOKEN = 'dropbox_refresh_token';
const LS_FOLDER_PATH = 'dropbox_folder_path';
const SS_PKCE_VERIFIER = 'dropbox_pkce_verifier';
// Prefix for cached GPX contents; one localStorage entry per Dropbox file id.
// The version suffix lets us invalidate the format later by bumping it.
const LS_CACHE_PREFIX = 'dropbox_gpx_cache_v1:';

// ---------------------------------------------------------------------------
// PKCE helpers
// ---------------------------------------------------------------------------

function base64UrlEncode(bytes) {
    let binary = '';
    const arr = new Uint8Array(bytes);
    for (let i = 0; i < arr.length; i++) {
        binary += String.fromCharCode(arr[i]);
    }
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

async function sha256(str) {
    const data = new TextEncoder().encode(str);
    return await crypto.subtle.digest('SHA-256', data);
}

function randomVerifier() {
    const arr = new Uint8Array(64);
    crypto.getRandomValues(arr);
    return base64UrlEncode(arr); // ~86 chars, within the required 43-128 range
}

// The Dropbox-API-Arg header must be ASCII; escape any non-ASCII characters
// (e.g. accented filenames) as \uXXXX so the header stays valid.
function httpHeaderSafeJson(obj) {
    return JSON.stringify(obj).replace(/[-￿]/g, (c) =>
        '\\u' + ('0000' + c.charCodeAt(0).toString(16)).slice(-4)
    );
}

// ---------------------------------------------------------------------------
// Dropbox client
// ---------------------------------------------------------------------------

class DropboxClient {
    constructor(appKey) {
        this.appKey = appKey;
        // The redirect URI must exactly match one registered in the App Console.
        // We strip the query string so it survives the OAuth round-trip cleanly.
        this.redirectUri = window.location.origin + window.location.pathname;
        this.accessToken = null;
        this.accessTokenExpiry = 0;
    }

    isConfigured() {
        return this.appKey && this.appKey !== 'YOUR_DROPBOX_APP_KEY';
    }

    isConnected() {
        return !!localStorage.getItem(LS_REFRESH_TOKEN);
    }

    // Step 1: send the user to Dropbox to authorize.
    async beginAuth() {
        const verifier = randomVerifier();
        sessionStorage.setItem(SS_PKCE_VERIFIER, verifier);
        const challenge = base64UrlEncode(await sha256(verifier));

        const params = new URLSearchParams({
            client_id: this.appKey,
            response_type: 'code',
            code_challenge: challenge,
            code_challenge_method: 'S256',
            redirect_uri: this.redirectUri,
            token_access_type: 'offline', // ask for a refresh token
            scope: DROPBOX_SCOPES
        });
        window.location.href = 'https://www.dropbox.com/oauth2/authorize?' + params.toString();
    }

    // Step 2: called on page load; if we came back from Dropbox with a ?code=,
    // exchange it for an access token + refresh token.
    async handleRedirect() {
        const params = new URLSearchParams(window.location.search);
        const error = params.get('error');
        if (error) {
            const desc = params.get('error_description') || error;
            this.cleanUrl();
            throw new Error('Dropbox authorization failed: ' + desc);
        }

        const code = params.get('code');
        if (!code) return false;

        const verifier = sessionStorage.getItem(SS_PKCE_VERIFIER);
        if (!verifier) {
            this.cleanUrl();
            throw new Error('Missing PKCE verifier; please try connecting again.');
        }

        const body = new URLSearchParams({
            code: code,
            grant_type: 'authorization_code',
            client_id: this.appKey,
            code_verifier: verifier,
            redirect_uri: this.redirectUri
        });

        const res = await fetch('https://api.dropboxapi.com/oauth2/token', {
            method: 'POST',
            body: body
        });
        sessionStorage.removeItem(SS_PKCE_VERIFIER);
        this.cleanUrl();

        if (!res.ok) {
            throw new Error('Token exchange failed: ' + (await res.text()));
        }
        const data = await res.json();
        if (data.refresh_token) {
            localStorage.setItem(LS_REFRESH_TOKEN, data.refresh_token);
        }
        this.accessToken = data.access_token;
        this.accessTokenExpiry = Date.now() + (data.expires_in || 14400) * 1000;
        return true;
    }

    // Remove the ?code=... from the address bar without reloading.
    cleanUrl() {
        window.history.replaceState({}, document.title, this.redirectUri);
    }

    // Return a valid access token, refreshing via the stored refresh token if needed.
    async getAccessToken() {
        if (this.accessToken && Date.now() < this.accessTokenExpiry - 60000) {
            return this.accessToken;
        }
        const refresh = localStorage.getItem(LS_REFRESH_TOKEN);
        if (!refresh) {
            throw new Error('Not connected to Dropbox.');
        }
        const body = new URLSearchParams({
            grant_type: 'refresh_token',
            refresh_token: refresh,
            client_id: this.appKey
        });
        const res = await fetch('https://api.dropboxapi.com/oauth2/token', {
            method: 'POST',
            body: body
        });
        if (!res.ok) {
            // The refresh token is no longer valid; force a reconnect.
            if (res.status === 400 || res.status === 401) {
                this.disconnect();
            }
            throw new Error('Dropbox session expired, please reconnect.');
        }
        const data = await res.json();
        this.accessToken = data.access_token;
        this.accessTokenExpiry = Date.now() + (data.expires_in || 14400) * 1000;
        return this.accessToken;
    }

    disconnect() {
        localStorage.removeItem(LS_REFRESH_TOKEN);
        this.accessToken = null;
        this.accessTokenExpiry = 0;
    }

    // Normalize a folder path for the Dropbox API: '' = root, otherwise '/Foo/Bar'.
    normalizePath(path) {
        if (!path) return '';
        let p = path.trim();
        if (p === '/' || p === '') return '';
        if (!p.startsWith('/')) p = '/' + p;
        if (p.length > 1 && p.endsWith('/')) p = p.slice(0, -1);
        return p;
    }

    async apiError(res) {
        let text = '';
        try { text = await res.text(); } catch (e) { /* ignore */ }
        return `Dropbox API error ${res.status}: ${text}`;
    }

    // List all .gpx files under a folder (recursively).
    async listGpxFiles(folderPath) {
        const token = await this.getAccessToken();
        const path = this.normalizePath(folderPath);
        const entries = [];

        let res = await fetch('https://api.dropboxapi.com/2/files/list_folder', {
            method: 'POST',
            headers: {
                'Authorization': 'Bearer ' + token,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ path: path, recursive: true, limit: 2000 })
        });
        if (!res.ok) throw new Error(await this.apiError(res));
        let data = await res.json();
        entries.push(...data.entries);

        while (data.has_more) {
            res = await fetch('https://api.dropboxapi.com/2/files/list_folder/continue', {
                method: 'POST',
                headers: {
                    'Authorization': 'Bearer ' + token,
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ cursor: data.cursor })
            });
            if (!res.ok) throw new Error(await this.apiError(res));
            data = await res.json();
            entries.push(...data.entries);
        }

        return entries.filter((e) =>
            e['.tag'] === 'file' && e.name.toLowerCase().endsWith('.gpx')
        );
    }

    // Download one file and return its text content.
    async downloadFile(path) {
        const token = await this.getAccessToken();
        const res = await fetch('https://content.dropboxapi.com/2/files/download', {
            method: 'POST',
            headers: {
                'Authorization': 'Bearer ' + token,
                'Dropbox-API-Arg': httpHeaderSafeJson({ path: path })
            }
        });
        if (!res.ok) throw new Error(await this.apiError(res));
        return await res.text();
    }

    // Download many files with a small concurrency limit. Returns
    // [{ filename, content }] or [{ filename, error }] per file. If a cache is
    // given, files already cached at their current revision are served from it
    // and freshly downloaded files are written back.
    async downloadAll(files, onProgress, cache) {
        const results = [];
        let done = 0;
        let idx = 0;
        const limit = Math.min(6, files.length);

        const worker = async () => {
            while (idx < files.length) {
                const file = files[idx++];
                try {
                    let content = cache ? cache.get(file.id, file.rev) : null;
                    if (content == null) {
                        content = await this.downloadFile(file.path_lower);
                        if (cache) cache.set(file.id, file.rev, file.name, content);
                    }
                    results.push({ filename: file.name, content: content });
                } catch (e) {
                    results.push({ filename: file.name, error: e.message });
                }
                done++;
                if (onProgress) onProgress(done, files.length);
            }
        };

        await Promise.all(Array.from({ length: limit }, worker));
        return results;
    }
}

// ---------------------------------------------------------------------------
// Persistent cache of downloaded GPX text
// ---------------------------------------------------------------------------

// Stores each downloaded file's text in localStorage, keyed by Dropbox file id
// and tagged with its revision, so a re-sync only downloads files that are new
// or whose contents changed. Keying by id (which is stable across renames)
// means a new revision overwrites the old one — no stale copies pile up.
class DropboxGpxCache {
    keyFor(id) {
        return LS_CACHE_PREFIX + id;
    }

    // Return the cached text for this file if we have it at this exact
    // revision, otherwise null (missing or the file changed on Dropbox).
    get(id, rev) {
        try {
            const raw = localStorage.getItem(this.keyFor(id));
            if (!raw) return null;
            const entry = JSON.parse(raw);
            return entry && entry.rev === rev ? entry.content : null;
        } catch (e) {
            return null;
        }
    }

    // Store a file's text. Gives up silently if storage is full so a sync
    // never fails just because the cache couldn't grow.
    set(id, rev, name, content) {
        try {
            localStorage.setItem(this.keyFor(id), JSON.stringify({ rev, name, content }));
            return true;
        } catch (e) {
            return false;
        }
    }

    // Number of files currently cached.
    count() {
        let n = 0;
        for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (k && k.startsWith(LS_CACHE_PREFIX)) n++;
        }
        return n;
    }

    // Drop every cached file. Returns how many entries were removed.
    clear() {
        const keys = [];
        for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (k && k.startsWith(LS_CACHE_PREFIX)) keys.push(k);
        }
        keys.forEach((k) => localStorage.removeItem(k));
        return keys.length;
    }
}

// ---------------------------------------------------------------------------
// UI wiring
// ---------------------------------------------------------------------------

document.addEventListener('DOMContentLoaded', async () => {
    const client = new DropboxClient(DROPBOX_APP_KEY);
    window.dropboxClient = client;
    const cache = new DropboxGpxCache();

    const connectBtn = document.getElementById('dropbox-connect');
    const connectedBox = document.getElementById('dropbox-connected');
    const folderInput = document.getElementById('dropbox-folder');
    const syncBtn = document.getElementById('dropbox-sync');
    const disconnectBtn = document.getElementById('dropbox-disconnect');
    const clearCacheBtn = document.getElementById('dropbox-clear-cache');
    const statusEl = document.getElementById('dropbox-status');

    if (!connectBtn) return; // Dropbox UI not present

    const showStatus = (msg) => { statusEl.textContent = msg || ''; };

    const updateUI = () => {
        if (!client.isConfigured()) {
            connectBtn.disabled = true;
            connectBtn.textContent = 'Dropbox not configured';
            showStatus('Set DROPBOX_APP_KEY in dropbox.js to enable this.');
            return;
        }
        const connected = client.isConnected();
        connectBtn.style.display = connected ? 'none' : 'block';
        connectedBox.style.display = connected ? 'block' : 'none';
    };

    const sync = async () => {
        const folder = folderInput.value.trim();
        localStorage.setItem(LS_FOLDER_PATH, folder);
        syncBtn.disabled = true;
        try {
            showStatus('Listing files…');
            const files = await client.listGpxFiles(folder);
            if (files.length === 0) {
                showStatus('No .gpx files found in ' + (folder || '/'));
                return;
            }
            const cachedCount = files.filter((f) => cache.get(f.id, f.rev) !== null).length;
            const toDownload = files.length - cachedCount;
            showStatus(toDownload > 0 ? `Downloading 0/${toDownload}…` : 'Loading from cache…');
            let downloaded = 0;
            const items = await client.downloadAll(files, () => {
                // Only count actual network downloads towards progress.
                if (downloaded < toDownload) {
                    downloaded++;
                    showStatus(`Downloading ${downloaded}/${toDownload}…`);
                }
            }, cache);
            window.mapVisualizer.clearTours();
            await window.mapVisualizer.loadGPXTexts(items);
            const ok = items.filter((i) => !i.error).length;
            const cacheNote = cachedCount > 0 ? ` (${cachedCount} from cache)` : '';
            showStatus(`Loaded ${ok} tour(s) from Dropbox${cacheNote}.`);
        } catch (e) {
            showStatus('Error: ' + e.message);
            if (!client.isConnected()) updateUI();
        } finally {
            syncBtn.disabled = false;
        }
    };

    connectBtn.addEventListener('click', () => {
        client.beginAuth().catch((e) => showStatus('Error: ' + e.message));
    });

    disconnectBtn.addEventListener('click', () => {
        client.disconnect();
        cache.clear();
        updateUI();
        showStatus('Disconnected.');
    });

    if (clearCacheBtn) {
        clearCacheBtn.addEventListener('click', () => {
            const n = cache.clear();
            showStatus(`Cleared ${n} cached file(s).`);
        });
    }

    syncBtn.addEventListener('click', sync);
    folderInput.value = localStorage.getItem(LS_FOLDER_PATH) || '';

    // Handle returning from the Dropbox authorization redirect.
    let justConnected = false;
    if (window.location.search.includes('code=') || window.location.search.includes('error=')) {
        try {
            justConnected = await client.handleRedirect();
        } catch (e) {
            showStatus('Error: ' + e.message);
        }
    }

    updateUI();

    if (client.isConnected() && window.mapVisualizer) {
        if (justConnected) {
            // First connect: let the user set their folder before the first sync,
            // so a Full Dropbox app doesn't recursively scan the entire account.
            showStatus('Connected. Set your folder above (if needed), then click Sync.');
        } else {
            // Returning visit with a saved folder: load it automatically.
            sync();
        }
    }
});
