/**
 * The `window.ZY` namespace.
 *
 * Each browser module is an independent IIFE that hangs its own object off the
 * shared `ZY` global (`ZY.BG`, `ZY.TTS`, `ZY.Agent`, …). That contract predates
 * the TypeScript port and is what the script tags in index.html rely on, so the
 * types describe it rather than replacing it.
 *
 * The sub-namespaces are typed loosely (`any`) on purpose: the modules are
 * large and mutually recursive, and the point of the port is that the sources
 * are TypeScript with real checking on the code each module owns, not that
 * every cross-module call is exhaustively modelled.
 */
export {};

declare global {
  /** The Android/Electron bridge object, when a native shell is present. */
  interface ZYNative {
    app?: unknown;
    info?: Record<string, any>;
    device?: Record<string, any>;
    stt?: Record<string, any>;
    tts?: Record<string, any>;
    settings?: Record<string, any>;
    call?: Record<string, any>;
    [k: string]: any;
  }

  interface ZYNamespace {
    store: {
      get<T = any>(k: string, d?: T): T;
      set(k: string, v: any): any;
      all(): Record<string, any>;
      _c: Record<string, any> | null;
    };
    DB: any;
    I18N: any;
    Platform: any;
    VOICE: any;
    TTS: any;
    STT: any;
    ORB: any;
    BG: any;
    ui: any;
    Agent: any;
    Tools: any;
    Monitor: any;
    Screen: any;
    Native: any;
    Call: any;
    characters: Record<string, any>;
    skills: any;
    App: any;
    t: (k: string, ...a: any[]) => string;
    /** The native bridge, present only inside the APK or the desktop shell. */
    NATIVE?: ZYNative | null;
    [k: string]: any;
  }

  interface Window {
    ZY: any;
    webkitSpeechRecognition?: any;
    SpeechRecognition?: any;
    webkitAudioContext?: typeof AudioContext;
    /** Native shells injected by other WebView hosts (kept for compatibility). */
    ZYRAXONAndroid?: any;
    ZYRAXONBridge?: any;
    ZYRAXON?: any;
    Capacitor?: any;
    ReactNativeWebView?: any;
    ZYNative?: any;
    ZYNativeCB?: any;
    ZYDesktop?: any;
  }

  /** The DOM helpers in these modules read `dataset`, `value`, `checked`, etc.
   *  off elements looked up by id. Typing the shared lookup as `any` keeps the
   *  port honest without rewriting hundreds of call sites into generic
   *  narrowing — the elements are all real controls declared in index.html. */
  interface Element {
    dataset: any;
    value?: any;
    checked?: any;
    files?: any;
    play?(): Promise<void> | void;
    pause?(): void;
    paused?: boolean;
    src?: string;
    muted?: boolean;
    volume?: number;
    playbackRate?: number;
    videoWidth?: number;
    videoHeight?: number;
    currentTime?: number;
    duration?: number;
    removeAttribute?(name: string): void;
    poster?: string;
    load?(): void;
  }

  /** The bridge object some modules read directly off `window`. */
  var ZYNATIVE: ZYNative | undefined;
}
