import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Helmet } from "react-helmet";
import { useMutation } from "@tanstack/react-query";
import {
  SkipBack,
  Play,
  Pause,
  Square,
  SkipForward,
  FolderOpen,
  Shuffle,
  Repeat,
  X,
  Search,
  ExternalLink,
} from "lucide-react";
import { Textarea } from "../components/Textarea";
import { Input } from "../components/Input";
import { postDriveResolve, DriveTrack } from "../endpoints/drive/resolve_POST.schema";
import {
  postDriveSearch,
  DriveFolderResult,
  SearchError,
} from "../endpoints/drive/search_POST.schema";
import { driveStreamUrl } from "../endpoints/drive/stream_GET.schema";
import styles from "./_index.module.css";

const BAR_COUNT = 19;

// Primary: stream through our backend (Drive blocks direct hotlinking from other origins).
const primaryUrl = (id: string) => driveStreamUrl(id);
const fallbackUrl = (id: string) =>
  `https://drive.usercontent.google.com/download?id=${encodeURIComponent(id)}&export=download&confirm=t`;

function fmt(sec: number | undefined): string {
  if (sec === undefined || !isFinite(sec)) return "--:--";
  const s = Math.max(0, Math.floor(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

export default function IndexPage() {
  const audioRef = useRef<HTMLAudioElement>(null);
  const triedFallback = useRef<string | null>(null);

  const [tracks, setTracks] = useState<DriveTrack[]>([]);
  const [current, setCurrent] = useState<number>(-1);
  const [playing, setPlaying] = useState(false);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(0.8);
  const [shuffle, setShuffle] = useState(false);
  const [repeat, setRepeat] = useState(false);
  const [durations, setDurations] = useState<Record<string, number>>({});
  const [showLoader, setShowLoader] = useState(true);
  const [linkText, setLinkText] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [bars, setBars] = useState<number[]>(() => Array(BAR_COUNT).fill(0));

  const resolveMutation = useMutation({
    mutationFn: (links: string[]) => postDriveResolve({ links }),
    onSuccess: (data) => {
      if (data.tracks.length > 0) {
        setTracks((prev) => {
          const ids = new Set(prev.map((t) => t.id));
          const merged = [...prev, ...data.tracks.filter((t) => !ids.has(t.id))];
          return merged;
        });
        setShowLoader(false);
        setLinkText("");
        setCurrent((c) => (c === -1 ? 0 : c));
      }
      setMessage(
        data.warnings.length > 0
          ? data.warnings.join(" · ")
          : data.tracks.length === 0
            ? "No se encontraron pistas de audio."
            : null
      );
    },
    onError: (e: Error) => setMessage(e.message),
  });

  // ----- Drive folder search -----
  const [searchText, setSearchText] = useState("");
  const [searchResults, setSearchResults] = useState<DriveFolderResult[] | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [loadingFolder, setLoadingFolder] = useState<string | null>(null);
  const [loadedFolders, setLoadedFolders] = useState<Record<string, number>>({});

  const searchMutation = useMutation({
    mutationFn: (query: string) => postDriveSearch({ query }),
    onMutate: () => setSearchError(null),
    onSuccess: (data) => setSearchResults(data.results),
    onError: (e: SearchError) => {
      setSearchResults(null);
      setSearchError(e.message);
    },
  });

  const submitSearch = () => {
    if (searchMutation.isPending) return;
    const q = searchText.trim();
    if (q.length < 2) {
      setSearchError("Escribe al menos 2 letras.");
      return;
    }
    searchMutation.mutate(q);
  };

  const loadFolder = (r: DriveFolderResult) => {
    setMessage(null);
    setLoadingFolder(r.id);
    resolveMutation.mutate([r.url], {
      onSuccess: (data) => {
        if (data.tracks.length > 0) {
          setLoadedFolders((prev) => ({ ...prev, [r.id]: data.tracks.length }));
        }
      },
      onSettled: () => setLoadingFolder(null),
    });
  };

  const currentTrack = current >= 0 ? tracks[current] : undefined;

  // Load the source whenever the selected track changes.
  const lastLoadedId = useRef<string | null>(null);
  useEffect(() => {
    const audio = audioRef.current;
    if (!audio) return;
    if (!currentTrack) {
      audio.removeAttribute("src");
      audio.load();
      lastLoadedId.current = null;
      return;
    }
    if (lastLoadedId.current === currentTrack.id) return;
    lastLoadedId.current = currentTrack.id;
    triedFallback.current = null;
    setPosition(0);
    setDuration(0);
    audio.src = primaryUrl(currentTrack.id);
    audio.load();
    if (playing) {
      audio.play().catch(() => setPlaying(false));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentTrack]);

  useEffect(() => {
    if (audioRef.current) audioRef.current.volume = volume;
  }, [volume]);

  // Fake spectrum animation (Drive audio is cross-origin, so real analysis isn't available).
  useEffect(() => {
    if (!playing) {
      setBars((b) => b.map(() => 0));
      return;
    }
    const id = window.setInterval(() => {
      setBars((prev) =>
        prev.map((v, i) => {
          const base = 0.25 + 0.55 * Math.sin((Date.now() / 400 + i) % 6.28) ** 2;
          const target = Math.min(1, base * (0.5 + Math.random() * 0.9));
          return v * 0.4 + target * 0.6;
        })
      );
    }, 90);
    return () => window.clearInterval(id);
  }, [playing]);

  const pickNext = useCallback(
    (dir: 1 | -1) => {
      if (tracks.length === 0) return -1;
      if (shuffle && dir === 1 && tracks.length > 1) {
        let n = current;
        while (n === current) n = Math.floor(Math.random() * tracks.length);
        return n;
      }
      return (current + dir + tracks.length) % tracks.length;
    },
    [tracks.length, shuffle, current]
  );

  const playTrack = (idx: number) => {
    if (idx < 0 || idx >= tracks.length) return;
    setMessage(null);
    if (idx === current) {
      audioRef.current?.play().catch(() => undefined);
      setPlaying(true);
      return;
    }
    setPlaying(true);
    setCurrent(idx);
  };

  const onPlay = () => {
    if (tracks.length === 0) {
      setShowLoader(true);
      return;
    }
    if (current === -1) {
      playTrack(0);
      return;
    }
    audioRef.current?.play().catch(() => setPlaying(false));
    setPlaying(true);
  };
  const onPause = () => {
    audioRef.current?.pause();
    setPlaying(false);
  };
  const onStop = () => {
    const a = audioRef.current;
    if (a) {
      a.pause();
      a.currentTime = 0;
    }
    setPosition(0);
    setPlaying(false);
  };
  const onNext = () => {
    const n = pickNext(1);
    if (n >= 0) playTrack(n);
  };
  const onPrev = () => {
    const a = audioRef.current;
    if (a && a.currentTime > 3) {
      a.currentTime = 0;
      return;
    }
    const n = pickNext(-1);
    if (n >= 0) playTrack(n);
  };

  const onEnded = () => {
    if (repeat && tracks.length === 1) {
      audioRef.current?.play().catch(() => undefined);
      return;
    }
    if (!repeat && !shuffle && current === tracks.length - 1) {
      setPlaying(false);
      setPosition(0);
      return;
    }
    onNext();
  };

  const onError = () => {
    const a = audioRef.current;
    if (!a || !currentTrack) return;
    if (triedFallback.current !== currentTrack.id) {
      triedFallback.current = currentTrack.id;
      a.src = fallbackUrl(currentTrack.id);
      a.load();
      if (playing) a.play().catch(() => undefined);
      return;
    }
    setPlaying(false);
    setMessage(
      `No se pudo reproducir "${currentTrack.title}". Verifica que el archivo sea público.`
    );
  };

  const removeTrack = (idx: number) => {
    setTracks((prev) => prev.filter((_, i) => i !== idx));
    if (idx === current) {
      audioRef.current?.pause();
      setPlaying(false);
      lastLoadedId.current = null;
      setCurrent(-1);
    } else if (idx < current) {
      setCurrent((c) => c - 1);
    }
  };

  const clearAll = () => {
    audioRef.current?.pause();
    lastLoadedId.current = null;
    setTracks([]);
    setCurrent(-1);
    setPlaying(false);
    setPosition(0);
    setDuration(0);
  };

  const submitLinks = () => {
    const links = linkText
      .split(/[\s,]+/)
      .map((l) => l.trim())
      .filter(Boolean);
    if (links.length === 0) {
      setMessage("Pega al menos un enlace de Drive.");
      return;
    }
    setMessage(null);
    resolveMutation.mutate(links);
  };

  const title = currentTrack
    ? `${current + 1}. ${currentTrack.title}`
    : "DRIVE AMP — pega un enlace de Drive para empezar";

  const totalTime = useMemo(
    () => tracks.reduce((acc, t) => acc + (durations[t.id] ?? 0), 0),
    [tracks, durations]
  );

  return (
    <>
      <Helmet>
        <title>Drive Amp — reproductor para enlaces de Drive</title>
        <meta
          name="description"
          content="Reproduce música desde enlaces públicos de Google Drive con una lista de reproducción estilo Winamp."
        />
      </Helmet>
      <div className={styles.page}>
        <div className={styles.stack}>
          <audio
            ref={audioRef}
            preload="metadata"
            onTimeUpdate={(e) => setPosition(e.currentTarget.currentTime)}
            onLoadedMetadata={(e) => {
              const d = e.currentTarget.duration;
              setDuration(d);
              if (currentTrack && isFinite(d)) {
                setDurations((prev) => ({ ...prev, [currentTrack.id]: d }));
              }
            }}
            onPlay={() => setPlaying(true)}
            onPause={() => {
              if (!audioRef.current?.ended) setPlaying(false);
            }}
            onEnded={onEnded}
            onError={onError}
          />

          {/* Main window */}
          <section className={styles.window} aria-label="Reproductor">
            <header className={styles.titleBar}>
              <span className={styles.titleGrip} />
              <span className={styles.titleText}>DRIVE AMP</span>
              <span className={styles.titleGrip} />
            </header>
            <div className={styles.mainBody}>
              <div className={styles.lcd}>
                <div className={styles.lcdTop}>
                  <div className={styles.timeBox}>
                    <span className={styles.status}>
                      {playing ? "▶" : currentTrack ? "❚❚" : "■"}
                    </span>
                    <span className={styles.time}>{fmt(position)}</span>
                  </div>
                  <div className={styles.viz} aria-hidden="true">
                    {bars.map((v, i) => (
                      <span
                        key={i}
                        className={styles.bar}
                        style={{ height: `${Math.round(v * 100)}%` }}
                      />
                    ))}
                  </div>
                </div>
                <div className={styles.marqueeBox}>
                  <span
                    key={title}
                    className={`${styles.marquee} ${title.length > 30 ? styles.scrolling : ""}`}
                  >
                    {title}
                  </span>
                </div>
                <div className={styles.lcdInfo}>
                  <span>DRIVE</span>
                  <span>{fmt(duration || undefined)}</span>
                  <span>{tracks.length} pistas</span>
                </div>
              </div>

              <input
                className={`${styles.range} ${styles.seek}`}
                type="range"
                min={0}
                max={duration || 0}
                step={0.1}
                value={Math.min(position, duration || 0)}
                disabled={!duration}
                aria-label="Posición"
                onChange={(e) => {
                  const v = Number(e.target.value);
                  if (audioRef.current) audioRef.current.currentTime = v;
                  setPosition(v);
                }}
              />

              <div className={styles.controlsRow}>
                <div className={styles.transport}>
                  <button className={styles.btn} onClick={onPrev} aria-label="Anterior">
                    <SkipBack size={12} />
                  </button>
                  <button
                    className={`${styles.btn} ${playing ? styles.btnOn : ""}`}
                    onClick={onPlay}
                    aria-label="Reproducir"
                  >
                    <Play size={12} />
                  </button>
                  <button className={styles.btn} onClick={onPause} aria-label="Pausa">
                    <Pause size={12} />
                  </button>
                  <button className={styles.btn} onClick={onStop} aria-label="Detener">
                    <Square size={11} />
                  </button>
                  <button className={styles.btn} onClick={onNext} aria-label="Siguiente">
                    <SkipForward size={12} />
                  </button>
                  <button
                    className={`${styles.btn} ${showLoader ? styles.btnOn : ""}`}
                    onClick={() => setShowLoader((s) => !s)}
                    aria-label="Abrir enlaces"
                    title="Abrir enlaces de Drive"
                  >
                    <FolderOpen size={12} />
                  </button>
                </div>
                <div className={styles.toggles}>
                  <button
                    className={`${styles.pill} ${shuffle ? styles.pillOn : ""}`}
                    onClick={() => setShuffle((s) => !s)}
                    aria-pressed={shuffle}
                  >
                    <Shuffle size={10} /> ALEAT
                  </button>
                  <button
                    className={`${styles.pill} ${repeat ? styles.pillOn : ""}`}
                    onClick={() => setRepeat((s) => !s)}
                    aria-pressed={repeat}
                  >
                    <Repeat size={10} /> REP
                  </button>
                </div>
              </div>

              <div className={styles.volRow}>
                <span className={styles.volLabel}>VOL</span>
                <input
                  className={`${styles.range} ${styles.vol}`}
                  type="range"
                  min={0}
                  max={1}
                  step={0.01}
                  value={volume}
                  aria-label="Volumen"
                  onChange={(e) => setVolume(Number(e.target.value))}
                />
                <span className={styles.volLabel}>{Math.round(volume * 100)}%</span>
              </div>
            </div>
          </section>

          {/* Loader window */}
          {showLoader && (
            <section className={styles.window} aria-label="Abrir enlaces">
              <header className={styles.titleBar}>
                <span className={styles.titleGrip} />
                <span className={styles.titleText}>ABRIR ENLACE DE DRIVE</span>
                <span className={styles.titleGrip} />
              </header>
              <div className={styles.loaderBody}>
                <Textarea
                  className={styles.linkInput}
                  value={linkText}
                  onChange={(e) => setLinkText(e.target.value)}
                  placeholder="Pega enlaces públicos de Drive (carpeta o archivo de audio), uno por línea"
                  rows={3}
                  onKeyDown={(e) => {
                    if ((e.metaKey || e.ctrlKey) && e.key === "Enter") submitLinks();
                  }}
                />
                <div className={styles.loaderActions}>
                  <button
                    className={`${styles.wideBtn} ${styles.wideBtnPrimary}`}
                    onClick={submitLinks}
                    disabled={resolveMutation.isPending}
                  >
                    {resolveMutation.isPending ? "CARGANDO..." : "CARGAR"}
                  </button>
                  <span className={styles.hint}>
                    Compartir → "Cualquier persona con el enlace"
                  </span>
                </div>
                {message && <p className={styles.message}>{message}</p>}
              </div>
            </section>
          )}

          {/* Playlist window */}
          <section className={styles.window} aria-label="Lista de reproducción">
            <header className={styles.titleBar}>
              <span className={styles.titleGrip} />
              <span className={styles.titleText}>LISTA DE REPRODUCCIÓN</span>
              <span className={styles.titleGrip} />
            </header>
            <div className={styles.playlistBody}>
              <ol className={styles.list}>
                {tracks.length === 0 && (
                  <li className={styles.empty}>— La lista está vacía —</li>
                )}
                {tracks.map((t, i) => (
                  <li
                    key={t.id}
                    className={`${styles.row} ${i === current ? styles.rowActive : ""}`}
                    onDoubleClick={() => playTrack(i)}
                  >
                    <button
                      className={styles.rowMain}
                      onClick={() => playTrack(i)}
                      title="Reproducir"
                    >
                      <span className={styles.rowNum}>{i + 1}.</span>
                      <span className={styles.rowTitle}>{t.title}</span>
                      <span className={styles.rowTime}>{fmt(durations[t.id])}</span>
                    </button>
                    <button
                      className={styles.rowRemove}
                      onClick={() => removeTrack(i)}
                      aria-label={`Quitar ${t.title}`}
                    >
                      <X size={10} />
                    </button>
                  </li>
                ))}
              </ol>
              <div className={styles.listFooter}>
                <button className={styles.wideBtn} onClick={() => setShowLoader(true)}>
                  + AGREGAR
                </button>
                <button className={styles.wideBtn} onClick={clearAll} disabled={tracks.length === 0}>
                  VACIAR
                </button>
                <span className={styles.total}>
                  {fmt(position)}/{fmt(duration || undefined)}
                  {totalTime > 0 ? ` · ${fmt(totalTime)}` : ""}
                </span>
              </div>
            </div>
          </section>

          {/* Drive folder search window */}
          <section className={styles.window} aria-label="Buscar carpetas de Drive">
            <header className={styles.titleBar}>
              <span className={styles.titleGrip} />
              <span className={styles.titleText}>BUSCAR CARPETAS DE DRIVE</span>
              <span className={styles.titleGrip} />
            </header>
            <div className={styles.searchBody}>
              <form
                className={styles.searchForm}
                onSubmit={(e) => {
                  e.preventDefault();
                  submitSearch();
                }}
              >
                <Input
                  className={styles.searchInput}
                  value={searchText}
                  onChange={(e) => setSearchText(e.target.value)}
                  placeholder="Ej: jazz, rock nacional, audiolibros..."
                  maxLength={100}
                  aria-label="Buscar carpetas públicas de Drive"
                />
                <button
                  type="submit"
                  className={`${styles.wideBtn} ${styles.wideBtnPrimary}`}
                  disabled={searchMutation.isPending}
                >
                  <Search size={10} /> {searchMutation.isPending ? "BUSCANDO..." : "BUSCAR"}
                </button>
              </form>

              {searchError && <p className={styles.message}>{searchError}</p>}
              {!showLoader && message && <p className={styles.message}>{message}</p>}
              {searchMutation.isPending && (
                <p className={styles.searchStatus}>
                  Buscando carpetas públicas… puede tardar unos segundos
                </p>
              )}

              {searchResults && !searchMutation.isPending &&
                (searchResults.length === 0 ? (
                  <p className={styles.searchStatus}>
                    No se encontraron carpetas públicas para esa búsqueda.
                  </p>
                ) : (
                  <ul className={styles.results}>
                    {searchResults.map((r) => (
                      <li key={r.id} className={styles.result}>
                        <div className={styles.resultInfo}>
                          <span className={styles.resultTitle} title={r.title}>
                            {r.title}
                          </span>
                          <span className={styles.resultMeta}>
                            {loadedFolders[r.id] !== undefined
                              ? `AGREGADA · ${loadedFolders[r.id]} pistas`
                              : "drive.google.com"}
                          </span>
                        </div>
                        <button
                          className={styles.wideBtn}
                          onClick={() => loadFolder(r)}
                          disabled={resolveMutation.isPending}
                        >
                          {loadingFolder === r.id ? "CARGANDO..." : "▶ CARGAR"}
                        </button>
                        <a
                          className={styles.linkBtn}
                          href={r.url}
                          target="_blank"
                          rel="noopener noreferrer"
                          aria-label={`Abrir ${r.title} en Drive`}
                          title="Abrir en Drive"
                        >
                          <ExternalLink size={11} />
                        </a>
                      </li>
                    ))}
                  </ul>
                ))}

              <p className={styles.hint}>
                Solo aparecen carpetas compartidas con "Cualquier persona con el enlace". Algunas
                pueden no tener audio.
              </p>
            </div>
          </section>
        </div>
      </div>
    </>
  );
}
