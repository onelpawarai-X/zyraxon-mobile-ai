/* ══════════════════════════════════════════════════════════════
   ZYRAXON Skills — the self-taught tool manual.
   Each entry: how the assistant calls a tool, and what it really
   does on-device or on the paired host. Rendered in the Skills pane.
   ══════════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  const SKILLS = [
    { name: 'todo_write', tag: 'অটোনমি', code: '{"todos":[{"id":"t1","title":"…","status":"in_progress"}]}',
      desc: 'লাইভ টাস্ক বোর্ড বানায়/আপডেট করে। কাজ শেষ না হলে সে থামবে না — প্রতিটা টুল রেজাল্টের পরে এটা কল করে পরের ধাপে যায়।' },
    { name: 'open_app', tag: 'ডিভাইস', code: '{"package":"com.whatsapp","name":"WhatsApp"}',
      desc: 'অ্যান্ড্রয়েডের রিয়েল অ্যাপ ওপেন করে (Intent/AppLauncher ব্রিজ, Capacitor হলে native plugin, ব্রাউজারে deep-link ফallback)।' },
    { name: 'launch_url', tag: 'ব্রাউজার', code: '{"url":"https://www.facebook.com","new_tab":true}',
      desc: 'ক্রোম/সিস্টেম ব্রাউজারে সত্যিকারের URL খোলে ও নতুন ট্যাব দেয়।' },
    { name: 'read_screen', tag: 'দৃষ্টি', code: '{"target":"current"}',
      desc: 'স্ক্রিনশট নেয়, টেক্সট/OCR বের করে ব্যাকগ্রাউন্ডে ইনজেক্ট করে — তাই অ্যাপ খোলার পরে সে দেখে নেয় কী হয়েছে।' },
    { name: 'tap', tag: 'নিয়ন্ত্রণ', code: '{"x":540,"y":1620}',
      desc: 'Accessibility সার্ভিস দিয়ে স্ক্রিনে রিয়েল ট্যাপ করে (ফলব্যাক: synthesizeTap / CDP)।' },
    { name: 'swipe', tag: 'নিয়ন্ত্রণ', code: '{"x1":540,"y1":1800,"x2":540,"y2":400}',
      desc: 'সত্যিকারের swipe — স্ক্রল, back gesture, slider সব।' },
    { name: 'type_text', tag: 'নিয়ন্ত্রণ', code: '{"text":"হ্যালো","enter":true}',
      desc: 'ফোকাস করা ইনপুটে রিয়েল টাইপ করে (ADB `input text` / Accessibility ACTION_SET_TEXT / native plugin)।' },
    { name: 'press_key', tag: 'নিয়ন্ত্রণ', code: '{"key":"home"}',
      desc: 'home / back / recents / enter / volume — হার্ডওয়্যার কী প্রেস।' },
    { name: 'notify', tag: 'ডিভাইস', code: '{"title":"ZYRAXON","body":"ওয়েবসাইট পাবলিশ হয়েছে"}',
      desc: 'অ্যান্ড্রয়েড নোটিফিকেশনে আসল মেসেজ পোস্ট করে।' },
    { name: 'clipboard_read', tag: 'ডিভাইস', code: '{}',
      desc: 'নেটিভ ক্লিপবোর্ড পড়ে — কম্পিউটার/ফোনের কপি করা কনটেন্ট আনে।' },
    { name: 'clipboard_write', tag: 'ডিভাইস', code: '{"text":"…"}',
      desc: 'ক্লিপবোর্ডে লেখে যাতে অন্য অ্যাপ পেস্ট করতে পারে।' },
    { name: 'share', tag: 'ডিভাইস', code: '{"text":"লিংক","url":"https://…"}',
      desc: 'অ্যান্ড্রয়েড শেয়ার শিট খোলে — লিংক/টেক্সট/ফাইল অন্য অ্যাপে পাঠায়।' },
    { name: 'read_url', tag: 'ওয়েব', code: '{"url":"https://…"}',
      desc: 'পেজ ফেচ করে টেক্সট বানায় (সার্ভার প্রক্সি, CORS নেই)। রিসার্চ ও ভেরিফিকেশনের জন্য।' },
    { name: 'read_media', tag: 'মাল্টিমোডাল', code: '{"url":"https://…/clip.mp4","modality":"video"}',
      desc: 'URL/লোকাল ফাইল থেকে video / audio / image / PDF পড়ে, base64 data-URL বানিয়ে multimodal মডেলে পাঠায় — নির্দিষ্ট অংশও পড়তে পারে (টাইমস্ট্যাম্প/পেজ)।' },
    { name: 'generate_file', tag: 'বিল্ড', code: '{"path":"index.html","content":"<html>…"}',
      desc: 'সার্ভারে আসল ফাইল লেখে (site workspace)। কনটেন্ট বানানো/রিপেয়ারের মূল হাতিয়ার।' },
    { name: 'publish_website', tag: 'পাবলিশ', code: '{"name":"portfolio","html":"<html>…","files":{"app.js":"…"}}',
      desc: 'আসল সাইট ডিস্কে লিখে পাবলিক পোর্টে সার্ভ করে, লাইভ URL রিটার্ন করে, আর URL ফেচ করে ভেরিফাই করে `verified:true` দেয়।' },
    { name: 'run_shell', tag: 'হোস্ট', code: '{"command":"ls -la"}',
      desc: 'পেয়ার করা ZYRAXON হোস্টে আসল কমান্ড চালায়, stdout/stderr ফেরত দেয়।' },
    { name: 'memory_write', tag: 'মেমরি', code: '{"key":"প্রিয় রং","value":"নীল"}',
      desc: 'ইউজার সম্পর্কে স্থায়ী তথ্য লোকাল DB-তে লিখে রাখে — ক্লাউড মুছলেও থাকবে।' },
  ];

  const PERMISSIONS = [
    { id: 'microphone', emoji: '🎙️', label: 'মাইক্রোফোন', why: 'তোমার বাংলা কথা শুনতে (Speech Recognition)' },
    { id: 'camera', emoji: '📷', label: 'ক্যামেরা', why: 'ছবি/ভিডিও দেখা ও ক্যাপচার করতে' },
    { id: 'geolocation', emoji: '📍', label: 'লোকেশন', why: 'আসেপাশের তথ্য দিতে' },
    { id: 'notifications', emoji: '🔔', label: 'নোটিফিকেশন', why: 'কাজ শেষ হলে জানাতে' },
    { id: 'clipboard-read', emoji: '📋', label: 'ক্লিপবোর্ড', why: 'কপি করা জিনিস পড়তে/লিখতে' },
    { id: 'screen', emoji: '🖥️', label: 'স্ক্রিন ক্যাপচার', why: 'স্ক্রিন দেখে পরের ধাপ ঠিক করতে' },
    { id: 'overlay', emoji: '🪟', label: 'ওভারে দেখানো', why: 'অন্য অ্যাপের উপরে কোড/টাস্ক বক্স দেখাতে (লাইভ কোড ওভারলে)' },
    { id: 'accessibility', emoji: '🤖', label: 'অ্যাক্সেসিবিলিটি সার্ভিস', why: 'রিয়েলি ট্যাপ/টাইপ/swipe করতে (অন্য অ্যাপ নিয়ন্ত্রণ)' },
    { id: 'storage', emoji: '💾', label: 'স্টোরেজ', why: 'ফাইল ও সাইট ডিস্কে লিখতে' },
    { id: 'install-apk', emoji: '📦', label: 'অ্যাপ ইনস্টল', why: 'নতুন APK ইনস্টল করতে' },
    { id: 'write-settings', emoji: '⚙️', label: 'সিস্টেম সেটিং বদল', why: 'সেটিং নিজে বদলাতে (রিস্টার্ট ছাড়া)' },
    { id: 'play-protect', emoji: '🛡️', label: 'Play Protect বন্ধ', why: 'যেকোনো APK ইনস্টল করতে — শুধু Allow চাপো' },
  ];

  const PLAY_NOTE = `গুগল প্লে প্রোটেক্ট (Play Protect) বন্ধ করার ধাপ:

১. অ্যাপ নিজেই আগে চেষ্টা করে: সেটিং বদলানোর অনুমতি (WRITE_SETTINGS) থাকলে সে নিজে থেকেই \`package_verifier_enable=0\` ও \`verifier_verify_adb_installs=0\` সেট করে দেয় — Play Protect বন্ধ।
২. অনুমতি না থাকলে উপরের "Play Protect বন্ধ" বাটনে চাপ দাও — অ্যাপ সোজা প্লে স্টোরের Play Protect স্ক্রিন খুলে দেবে। ওখানে শুধু "Scan apps with Play Protect" টগলটা বন্ধ করো।
৩. অ্যাক্সেসিবিলিটি সার্ভিস ব্যবহার করতে Play Protect বন্ধ করা লাগে না, কিন্তু যেকোনো APK নিজে ইনস্টল/সাইন করতে লাগে।
৪. ADB থাকলে নিরবচ্ছিন্ন: \`adb shell settings put global package_verifier_enable 0\` এবং \`adb shell settings put global verifier_verify_adb_installs 0\`।

যেসব অনুমতি উপরে Allow পপ-আপে আসে না (ওভারে, অ্যাক্সেসিবিলিটি, সিস্টেম সেটিং, অ্যাপ ইনস্টল, স্ক্রিন ক্যাপচার) — সেগুলোর জন্য অ্যাপ নিজেই সিস্টেমের ঠিক পর্দাটা খুলে দেয়। শুধু ওখানে টগলটা অন করে ফিরে আসো। কোনো মেনু খুঁজতে হবে না।`;

  global.ZY = global.ZY || {};
  global.ZY.skills = SKILLS;
  global.ZY.permissions = PERMISSIONS;
  global.ZY.playNote = PLAY_NOTE;
})(window);
