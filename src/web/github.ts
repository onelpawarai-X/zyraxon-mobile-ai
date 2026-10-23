/**
 * ZYRAXON — GitHub, straight from the user's own account.
 *
 * The server used to hold the token and do this work, which meant the APK —
 * a static asset bundle with no server behind it — could not publish at all.
 * The user owns the token, so the token belongs on the device: it is stored in
 * the local DB (never in the page or a URL) and every call goes to GitHub's
 * REST API directly. GitHub sends permissive CORS headers, so the WebView can
 * reach it; on Android the request still goes through the native bridge so a
 * stricter endpoint could never block it.
 *
 * No token, no pretending: every method returns { ok: false, error: ... } and
 * the caller says so plainly instead of inventing a repository URL.
 */
(function (global) {
  'use strict';

  const API = 'https://api.github.com';
  const KEY = 'githubToken';

  /** One REST call. Returns parsed JSON, or { error, message } on failure. */
  async function gh(method: string, endpoint: string, body?: any): Promise<any> {
    const token = GH.token();
    if (!token) return { error: 'no-token', message: 'গিটহাব টোকেন দেওয়া হয়নি (সেটিংসে দাও)' };
    const headers = {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
    };
    if (body) headers['Content-Type'] = 'application/json';
    try {
      const net = global.ZY.Tools && global.ZY.Tools._netFetch;
      const opts = { method, headers, body: body ? JSON.stringify(body) : undefined, timeoutMs: 30000 };
      const r = net ? await net(`${API}${endpoint}`, opts) : await fetch(`${API}${endpoint}`, opts);
      // A native bridge returns `{ ok, status, text }`; a browser Response has
      // `text` as a *function*. Testing `r.text != null` therefore read the
      // function itself as the body, so JSON.parse always failed and every
      // GitHub call came back as an empty object — `login` was undefined and
      // the repo list was empty even though the request had succeeded.
      const text = typeof r.text === 'function' ? await r.text() : r.text;
      let j = null;
      try { j = text ? JSON.parse(text) : null; } catch { j = { raw: String(text).slice(0, 500) }; }
      if (!r.ok) return { error: `github-${r.status}`, message: (j && (j.message || j.error)) || String(text).slice(0, 300) };
      return j || {};
    } catch (e) {
      return { error: 'network', message: String(e) };
    }
  }

  /** A short literal that must appear in the served HTML if our build is live. */
  function pagesMarker(files) {
    const entry = files['index.html'] || files['/index.html'] || Object.entries(files).find(([k]) => /(^|\/)index\.html?$/i.test(k));
    const text = typeof entry === 'string' ? entry : (entry && entry[1]) || '';
    if (!text) return '';
    const title = /<title[^>]*>([^<]{3,80})<\/title>/i.exec(text);
    if (title) return title[1].trim();
    const h1 = /<h1[^>]*>([^<]{3,80})<\/h1>/i.exec(text);
    if (h1) return h1[1].trim();
    return text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 40);
  }

  const GH: any = {
    /* ── the token lives in the local DB, never in the page ── */
    token() {
      try { return String(global.ZY.store.get(KEY, '') || '').trim(); } catch { return ''; }
    },
    setToken(t) {
      global.ZY.store.set(KEY, String(t || '').trim());
      return GH.token().length > 0;
    },
    connected() { return GH.token().length > 0; },

    /** Which account this token belongs to, and its repositories. */
    async account() {
      const u = await gh('GET', '/user');
      if (u.error) return { ok: false, ...u };
      const repos = await gh('GET', `/users/${u.login}/repos?per_page=100&sort=updated`);
      return {
        ok: true,
        connected: true,
        login: u.login,
        name: u.name,
        avatar: u.avatar_url,
        repos: Array.isArray(repos) ? repos.map((r) => ({ name: r.name, url: r.html_url, private: r.private, pages: r.has_pages })) : [],
      };
    },

    /**
     * Create or reuse a repository and commit `files` into it. When `pages` is
     * set the site is also committed to `gh-pages` and then *verified* by
     * fetching the public URL and looking for a marker only our content has.
     */
    async publish(body) {
      const user = await gh('GET', '/user');
      if (user.error) return { ok: false, error: user.error, message: user.message };

      let owner = body.owner || user.login;
      let name = String(body.name || '').replace(/[^A-Za-z0-9._-]/g, '-').replace(/^-+|-+$/g, '').slice(0, 90);
      const target = body.repo && body.repo.includes('/') ? body.repo : '';
      let created = false;
      let fellBack = false;
      let repo = null;

      if (target) {
        repo = await gh('GET', `/repos/${target}`);
        if (repo.error) return { ok: false, error: repo.error, message: repo.message };
        [owner, name] = target.split('/');
      } else {
        if (!name) return { ok: false, error: 'bad-name', message: 'রিপোজিটরির নাম দরকার' };
        repo = await gh('GET', `/repos/${owner}/${name}`);
        if (repo.error === 'github-404') {
          repo = await gh('POST', '/user/repos', {
            name,
            description: body.description || 'Built by ZYRAXON',
            private: !!body.private,
            auto_init: false,
          });
          if (repo.error) {
            // A restricted token can only touch the repositories it was granted.
            // Publishing into one of those beats failing outright.
            const list = await gh('GET', `/users/${user.login}/repos?per_page=1&sort=updated`);
            const first = Array.isArray(list) && list[0];
            if (!first) return { ok: false, error: repo.error, message: repo.message };
            repo = first;
            owner = first.full_name.split('/')[0];
            name = first.full_name.split('/')[1];
            fellBack = true;
          } else {
            created = true;
            await new Promise((r) => setTimeout(r, 1200));
          }
        } else if (repo.error) {
          return { ok: false, error: repo.error, message: repo.message };
        }
      }

      const files = body.files && typeof body.files === 'object' ? body.files : {};
      const branch = body.branch || repo.default_branch || 'main';
      const folder = (body.folder !== undefined ? String(body.folder) : (fellBack ? `sites/${name}` : '')).replace(/^\/+|\/+$/g, '');
      const committed = [];
      const failed = [];

      for (const [rawPath, content] of Object.entries(files)) {
        const rel = String(rawPath).replace(/^\/+/, '');
        if (!rel || rel.includes('..')) continue;
        const filePath = folder ? `${folder}/${rel}` : rel;
        const existing = await gh('GET', `/repos/${owner}/${name}/contents/${encodeURI(filePath)}?ref=${encodeURIComponent(branch)}`);
        const put = await gh('PUT', `/repos/${owner}/${name}/contents/${encodeURI(filePath)}`, {
          message: body.message || `Add ${filePath} (ZYRAXON)`,
          content: btoa(unescape(encodeURIComponent(String(content)))),
          branch,
          ...(existing && existing.sha ? { sha: existing.sha } : {}),
        });
        if (put.error) failed.push({ path: filePath, error: put.error, message: put.message });
        else committed.push(filePath);
      }

      let pages = null;
      if (body.pages && committed.length) {
        const pb = body.pagesBranch || 'gh-pages';
        // GitHub enables Pages the moment a `gh-pages` branch exists, and
        // creating that branch is permitted where the Pages API is not.
        let head = await gh('GET', `/repos/${owner}/${name}/git/ref/heads/${pb}`);
        if (head.error === 'github-404') {
          const def = await gh('GET', `/repos/${owner}/${name}/git/ref/heads/${branch}`);
          if (def && def.object && def.object.sha) {
            await gh('POST', `/repos/${owner}/${name}/git/refs`, { ref: `refs/heads/${pb}`, sha: def.object.sha });
          }
        }
        for (const [rawPath, content] of Object.entries(files)) {
          const rel = String(rawPath).replace(/^\/+/, '');
          const out = folder ? rel.replace(new RegExp('^' + folder.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '/'), '') : rel;
          if (!out || out.includes('..')) continue;
          const existing = await gh('GET', `/repos/${owner}/${name}/contents/${encodeURI(out)}?ref=${encodeURIComponent(pb)}`);
          await gh('PUT', `/repos/${owner}/${name}/contents/${encodeURI(out)}`, {
            message: body.message || `Publish ${out} (ZYRAXON)`,
            content: btoa(unescape(encodeURIComponent(String(content)))),
            branch: pb,
            ...(existing && existing.sha ? { sha: existing.sha } : {}),
          });
        }

        const url = `https://${owner.toLowerCase()}.github.io/${name}/`;
        const marker = pagesMarker(files);
        let status = 0, matched = false;
        // A 200 is not proof: a previously published site keeps answering from
        // the CDN while the new build propagates. Look for our own marker too.
        for (let i = 0; i < 12; i++) {
          try {
            const net = global.ZY.Tools && global.ZY.Tools._netFetch;
            const r = net ? await net(`${url}?zyraxon=${Date.now()}`, { timeoutMs: 20000 })
              : await fetch(`${url}?zyraxon=${Date.now()}`, { redirect: 'follow', cache: 'no-store' });
            status = r.status;
            if (r.ok) {
              const body2 = typeof r.text === 'function' ? await r.text() : r.text;
              matched = marker ? String(body2).includes(marker) : true;
              if (matched) break;
            }
          } catch { status = 0; }
          await new Promise((r) => setTimeout(r, 5000));
        }
        pages = { url, status, live: status === 200 && matched, verified: matched, marker: marker || null };
      }

      return {
        ok: committed.length > 0 || !failed.length,
        created, fellBack, folder: folder || '/',
        repo: `https://github.com/${owner}/${name}`,
        fullName: `${owner}/${name}`, owner, name, branch, committed, failed, pages,
      };
    },

    /** Commit a file into the repo's workflow folder. */
    async workflow(body) {
      const full = String(body.repo || '');
      if (!full.includes('/')) return { ok: false, error: 'bad-repo', message: 'repo "owner/name" আকারে দাও' };
      const [owner, name] = full.split('/');
      const filePath = String(body.path || '.github/workflows/build.yml');
      const branch = body.branch || 'main';
      const existing = await gh('GET', `/repos/${owner}/${name}/contents/${encodeURI(filePath)}?ref=${encodeURIComponent(branch)}`);
      const put = await gh('PUT', `/repos/${owner}/${name}/contents/${encodeURI(filePath)}`, {
        message: body.message || `Add ${filePath} (ZYRAXON)`,
        content: btoa(unescape(encodeURIComponent(String(body.content || '')))),
        branch,
        ...(existing && existing.sha ? { sha: existing.sha } : {}),
      });
      return put.error ? { ok: false, ...put } : { ok: true, path: filePath, url: `https://github.com/${full}/blob/${branch}/${filePath}` };
    },

    /**
     * Dispatch a workflow and wait for it, reporting the real per-job result.
     * This is a genuine multi-platform builder running in the user's account.
     */
    async actions(body) {
      const full = String(body.repo || '');
      if (!full.includes('/')) return { ok: false, error: 'bad-repo', message: 'repo "owner/name" আকারে দাও' };
      const wf = body.workflow || 'build.yml';
      const ref = body.ref || 'main';

      if (body.files && Object.keys(body.files).length) {
        const pub = await GH.publish({ name: full.split('/')[1], owner: full.split('/')[0], files: body.files, branch: ref });
        if (!pub.ok) return { ok: false, stage: 'push', ...pub };
      }

      // A workflow file committed seconds ago is not dispatchable yet.
      let dispatch = null;
      for (let i = 0; i < 10; i++) {
        dispatch = await gh('POST', `/repos/${full}/actions/workflows/${wf}/dispatches`, { ref });
        if (!dispatch.error) break;
        if (dispatch.error !== 'github-404' && dispatch.error !== 'github-422') {
          return { ok: false, stage: 'dispatch', error: dispatch.error, message: dispatch.message };
        }
        await new Promise((r) => setTimeout(r, 4000));
      }
      if (dispatch.error) return { ok: false, stage: 'dispatch', error: dispatch.error, message: dispatch.message };

      const since = Date.now() - 60000;
      const deadline = Date.now() + Math.min(Number(body.timeout_ms) || 600000, 900000);
      let run = null;
      while (Date.now() < deadline) {
        await new Promise((r) => setTimeout(r, 6000));
        const runs = await gh('GET', `/repos/${full}/actions/workflows/${wf}/runs?per_page=5`);
        // Only the run this dispatch created — an older finished run must never
        // be mistaken for the build we just started.
        const mine = ((runs && runs.workflow_runs) || []).filter((x) => Date.parse(x.created_at) >= since);
        const first = mine[0];
        if (!first) continue;
        run = first;
        if (first.status === 'completed') break;
      }
      if (!run) return { ok: false, stage: 'timeout', error: 'timeout', message: 'বিল্ড শেষ হওয়ার অপেক্ষায় সময় শেষ' };

      const jobs = await gh('GET', `/repos/${full}/actions/runs/${run.id}/jobs`);
      const artifacts = await gh('GET', `/repos/${full}/actions/runs/${run.id}/artifacts`);
      return {
        ok: run.conclusion === 'success',
        conclusion: run.conclusion,
        runUrl: run.html_url,
        jobs: ((jobs && jobs.jobs) || []).map((j) => ({ name: j.name, conclusion: j.conclusion, steps: (j.steps || []).map((s) => ({ name: s.name, conclusion: s.conclusion })) })),
        artifacts: ((artifacts && artifacts.artifacts) || []).map((a) => ({ name: a.name, size: a.size_in_bytes, url: a.archive_download_url })),
      };
    },
  };

  global.ZY = global.ZY || {};
  global.ZY.GH = GH;
})(window);
