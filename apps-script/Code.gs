/**
 * Golden Man registration relay (Google Apps Script web app).
 *
 * The mod sends:  { username, uuid, profile_id, serverId }
 * 1. Proves the player owns the account: the mod has just told Mojang it "joined" serverId,
 *    which only the real logged-in account can do. We ask Mojang to confirm.
 * 2. Asks the GitHub checker (Tedsoup/GoldenMan) to verify the profile.
 * 3. Replies { ok, message }, which the mod shows in chat.
 *
 * Setup: Project Settings -> Script Properties -> add GITHUB_TOKEN
 *        (fine-grained GitHub token, only the GoldenMan repo, "Contents: Read and write").
 * Deploy: Deploy -> New deployment -> Web app -> Execute as: Me, Who has access: Anyone.
 */
const REPO = 'Tedsoup/GoldenMan';

function doPost(e) {
  try {
    const req = JSON.parse(e.postData.contents);
    const name = String(req.username || '');
    const uuid = String(req.uuid || '').replace(/-/g, '').toLowerCase();
    const profile = String(req.profile_id || '').toLowerCase();
    const serverId = String(req.serverId || '');

    if (!/^[A-Za-z0-9_]{1,16}$/.test(name) || !/^[0-9a-f]{32}$/.test(uuid)
        || !/^[0-9a-f-]{32,36}$/.test(profile) || !/^-?[0-9a-f]{1,40}$/.test(serverId)) {
      return reply(false, 'Bad registration request.');
    }

    // Slow down spam: one attempt per account per minute
    const cache = CacheService.getScriptCache();
    if (cache.get('reg:' + uuid)) return reply(false, 'Please wait a minute before trying again.');
    cache.put('reg:' + uuid, '1', 60);

    // 1. Ownership check with Mojang
    const mojang = UrlFetchApp.fetch(
      'https://sessionserver.mojang.com/session/minecraft/hasJoined?username=' + encodeURIComponent(name)
        + '&serverId=' + encodeURIComponent(serverId),
      { muteHttpExceptions: true });
    if (mojang.getResponseCode() !== 200) {
      return reply(false, "Couldn't confirm you own this Minecraft account. Try again in a moment.");
    }
    const player = JSON.parse(mojang.getContentText());
    if (String(player.id).toLowerCase() !== uuid) return reply(false, 'That account does not match.');

    // 2. Start the GitHub checker
    const token = PropertiesService.getScriptProperties().getProperty('GITHUB_TOKEN');
    const gh = UrlFetchApp.fetch('https://api.github.com/repos/' + REPO + '/dispatches', {
      method: 'post',
      contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + token, Accept: 'application/vnd.github+json' },
      payload: JSON.stringify({
        event_type: 'register',
        client_payload: { uuid: uuid, profile_id: profile, name: player.name }
      }),
      muteHttpExceptions: true
    });
    if (gh.getResponseCode() !== 204) {
      return reply(false, 'The registration service had a problem (' + gh.getResponseCode() + '). Tell the developer.');
    }
    return reply(true, 'Registered! Your profile is being checked; your status updates within a few minutes.');
  } catch (err) {
    return reply(false, 'Registration error: ' + err);
  }
}

/** Visiting the URL in a browser just shows that it's running. */
function doGet() {
  return reply(true, 'Golden Man registration service is running.');
}

function reply(ok, message) {
  return ContentService.createTextOutput(JSON.stringify({ ok: ok, message: message }))
    .setMimeType(ContentService.MimeType.JSON);
}
