/**
 * Golden Man registration relay (Google Apps Script web app).
 *
 * The mod sends:  { username, uuid, profile_id, action }   (action: "register" or "quit")
 * No session token or password is ever sent. We check with Mojang's public profile lookup
 * that the username belongs to that UUID, then ask the GitHub checker (Tedsoup/GoldenMan) to act.
 * The checker itself confirms the UUID is a member of that SkyBlock profile through the Hypixel API.
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
    const action = req.action === 'quit' ? 'quit' : 'register';

    if (!/^[A-Za-z0-9_]{1,16}$/.test(name) || !/^[0-9a-f]{32}$/.test(uuid)
        || !/^[0-9a-f-]{32,36}$/.test(profile)) {
      return reply(false, 'Bad registration request.');
    }

    // Slow down spam: one attempt per account per minute
    const cache = CacheService.getScriptCache();
    if (cache.get(action + ':' + uuid)) return reply(false, 'Please wait a minute before trying again.');
    cache.put(action + ':' + uuid, '1', 60);

    // 1. Check that the username belongs to this UUID (Mojang's public profile lookup)
    const mojang = UrlFetchApp.fetch('https://sessionserver.mojang.com/session/minecraft/profile/' + uuid,
      { muteHttpExceptions: true });
    if (mojang.getResponseCode() !== 200) return reply(false, "Couldn't find that Minecraft account.");
    const player = JSON.parse(mojang.getContentText());
    if (String(player.name).toLowerCase() !== name.toLowerCase()) return reply(false, 'That account does not match.');

    // 2. Start the GitHub checker
    const token = PropertiesService.getScriptProperties().getProperty('GITHUB_TOKEN');
    const gh = UrlFetchApp.fetch('https://api.github.com/repos/' + REPO + '/dispatches', {
      method: 'post',
      contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + token, Accept: 'application/vnd.github+json' },
      payload: JSON.stringify({
        event_type: action,
        client_payload: { uuid: uuid, profile_id: profile, name: player.name }
      }),
      muteHttpExceptions: true
    });
    if (gh.getResponseCode() !== 204) {
      return reply(false, 'The registration service had a problem (' + gh.getResponseCode() + '). Tell the developer.');
    }
    if (action === 'quit') return reply(true, 'You were removed from the Golden Man list.');
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
