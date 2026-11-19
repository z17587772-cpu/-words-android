# words Android · v1.5.0

Kotlin + Android Views + SQLite vocabulary app.

## Quick Browse
- Unlimited cards; swipe left for next and right for previous.
- The next-word button is removed; the card itself handles horizontal gestures.
- Masking preference: none, hide English, or hide Chinese.
- No daily target or review-state writes.
- Optional swipe sound and haptic feedback.

## Daily Quiz
- Daily target controls the queue length.
- Four-choice English-to-Chinese, Chinese-to-English, or mixed alternating mode.
- After selection, remain on the question screen and mark the correct option / selected wrong option.
- Tap any option or the explanation action to open the answer detail.
- Each submitted answer is recorded exactly once; skip is not rated.
- Larger question card and transitions between quiz states.

## Answer explanation

1. Result and correct choice.
2. Headword, phonetic, part of speech and TTS.
3. Local curriculum meaning.
4. Example sentence with target word highlighted, Chinese translation and sentence playback.
5. Optional online definitions, forms, examples, synonyms, antonyms and available Chinese translations.
6. Attribution to FreeDictionaryAPI.com / Wiktionary and CC BY-SA 4.0.

If the local wordbook does not contain an example translation, the app may request a MyMemory machine-translation reference when online enrichment is enabled. The output is labelled as a reference translation and cached locally. Anonymous MyMemory usage is limited; the app only asks for a small number of examples per viewed word.

## Feedback
- Sound uses Android ToneGenerator short notification tones.
- Haptics use a short VibrationEffect.
- Sound and vibration switches are independent under My > Answer and navigation feedback; sound defaults off and haptics defaults on.

## Online data limits
- The API does not provide reliable word-root, collocation and exam-frequency data for every entry. These are not invented.
- Online fields vary by word; no-data fields are omitted.
- Local word meanings, study queue, favorite list, TTS and cached details remain available offline.

## Build
JDK 17, Android SDK Platform 35 / Build Tools 35.0.0, Gradle 8.9.

```bash
gradle --no-daemon clean assembleDebug
```
