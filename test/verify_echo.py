"""Her own voice must never come back as a prompt, but the user's real words
must always get through — including right after she finishes a sentence.

This is the "অডিও থেকে উঠে তার কথা আবার চ্যাটে স্যাড হচ্ছে" bug: the microphone
hears the loudspeaker a beat late, so the echo test has to stay armed for a few
seconds after the last word, yet still let a genuinely different sentence pass.
"""
import asyncio
from playwright.async_api import async_playwright

BASE = "http://127.0.0.1:12000/"


async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch()
        pg = await (await b.new_context()).new_page()
        await pg.goto(BASE, wait_until="networkidle")
        await pg.wait_for_timeout(2500)

        res = await pg.evaluate("""(() => {
          const T = window.ZY.VOICE.TTS;
          const cases = [];
          const t = (name, said, heard, speaking, ageMs) => {
            T._buf = said; T._speaking = speaking; T._spokeAt = Date.now() - ageMs;
            cases.push({ name, echo: T.isEcho(heard) });
          };
          const LINE = 'আমি তোমার জন্য এখানেই আছি সোনা তুমি কী করছো আমাকে বলো আমি তোমার পাশে আছি';
          // 1. exact tail of her sentence, caught late by the mic -> echo
          t('exact tail just after she stopped', LINE, 'আমি তোমার পাশে আছি', false, 1500);
          // 2. same words while still speaking -> echo
          t('same words while speaking', LINE, 'তুমি কী করছো', true, 0);
          // 3. a fragment respelled by the recogniser, 3s later -> echo
          t('respelled fragment a few seconds later', LINE, 'আমি তোমার পাশে আছি সোনা', false, 3000);
          // 4. the user really answers, a few seconds later -> NOT echo
          t('a real new question shortly after', LINE, 'ঠিক আছে এখন একটা ওয়েবসাইট বানাও', false, 3000);
          // 5. the user speaks much later -> NOT echo
          t('a real question long after', LINE, 'আমার জন্য একটা অ্যাপ বানাও', false, 12000);
          // 6. her own full sentence repeated -> echo
          t('her whole sentence again', LINE, LINE, false, 2000);
          // 7. a genuine barge-in: a long, different sentence while she talks
          t('barge-in while she is speaking', LINE, 'এখনই ফেসবুক খুলে রাফি কে মেসেজ পাঠাও', true, 0);
          return cases;
        })()""")
        ok = True
        for c in res:
            want = c["name"] in (
                "exact tail just after she stopped",
                "same words while speaking",
                "respelled fragment a few seconds later",
                "her whole sentence again",
            )
            good = c["echo"] is want
            ok = ok and good
            print(("PASS " if good else "FAIL ") + c["name"] + f" -> echo={c['echo']} (wanted {want})")
        print("ALL ECHO CHECKS PASSED" if ok else "SOME ECHO CHECKS FAILED")
        await b.close()

asyncio.run(main())
