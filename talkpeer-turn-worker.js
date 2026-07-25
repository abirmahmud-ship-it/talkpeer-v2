// TalkPeer TURN credential broker — deploy this as a Cloudflare Worker.
//
// This exists because Cloudflare's TURN key is a long-lived secret that must
// NEVER be shipped to a browser/app — anyone who saw it could mint unlimited
// credentials on your account. This tiny Worker holds that secret (as an
// encrypted environment variable, set up in the Cloudflare dashboard — never
// pasted into this file) and hands out fresh, short-lived, safe-to-share
// credentials to whichever TalkPeer client asks.
//
// Access control: this Worker's URL is public (it has to be — the app has to
// call it), so without a check, ANYONE who finds the URL could mint
// themselves TURN credentials and relay their own unrelated traffic through
// your Cloudflare account. APP_SHARED_SECRET below is a simple shared
// password the app sends with every request; requests without the right one
// are rejected before ever touching your real Cloudflare TURN key. This
// isn't unbreakable — anyone who reads the app's own source code could still
// extract it — but it stops casual/automated abuse of the bare URL, which is
// the realistic threat here.
//
// Deploy: Cloudflare dashboard → Workers & Pages → Create Worker → paste
// this in the online editor → Deploy. Then add three secrets under
// Settings → Variables and Secrets (all as "Secret" type, not plain text):
//   TURN_KEY_ID         — from Cloudflare Calls/Realtime → TURN → your key
//   TURN_KEY_API_TOKEN  — the API token shown once when you created that key
//   APP_SHARED_SECRET   — any long random string you make up yourself; must
//                          exactly match TURN_BROKER_SECRET in the app's code

export default {
  async fetch(request, env) {
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, X-TalkPeer-Auth',
    };

    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders });
    }

    const providedSecret = request.headers.get('X-TalkPeer-Auth');
    if (!env.APP_SHARED_SECRET || providedSecret !== env.APP_SHARED_SECRET) {
      return new Response(JSON.stringify({ error: 'Unauthorized' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json', ...corsHeaders },
      });
    }

    try {
      const resp = await fetch(
        `https://rtc.live.cloudflare.com/v1/turn/keys/${env.TURN_KEY_ID}/credentials/generate-ice-servers`,
        {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${env.TURN_KEY_API_TOKEN}`,
            'Content-Type': 'application/json',
          },
          // 4 hours — comfortably longer than any real meeting, but far
          // shorter than the original 24h. If a credential is ever stolen
          // or leaked, this shrinks how long it stays usable, since there's
          // no automatic hard spending cap on the Cloudflare side to fall
          // back on if one gets abused. Each request here mints a fresh
          // credential anyway; nothing is reused.
          body: JSON.stringify({ ttl: 14400 }),
        }
      );

      if (!resp.ok) {
        const errText = await resp.text();
        return new Response(JSON.stringify({ error: `Cloudflare TURN API error: ${errText}` }), {
          status: 502,
          headers: { 'Content-Type': 'application/json', ...corsHeaders },
        });
      }

      const data = await resp.json();
      return new Response(JSON.stringify(data), {
        headers: { 'Content-Type': 'application/json', ...corsHeaders },
      });
    } catch (err) {
      return new Response(JSON.stringify({ error: String(err) }), {
        status: 500,
        headers: { 'Content-Type': 'application/json', ...corsHeaders },
      });
    }
  },
};
