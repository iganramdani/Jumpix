(() => {
  'use strict';

  // =========================================================================
  // 1. BROWSER CSV TELEMETRY OSCILLOSCOPE (100% Client-Side)
  // =========================================================================
  const oscCanvas = document.getElementById('oscCanvas');
  const oscCtx = oscCanvas.getContext('2d');
  const dropzone = document.getElementById('dropzone');
  const csvFileInput = document.getElementById('csvFileInput');
  const loadSampleBtn = document.getElementById('loadSampleBtn');
  const csvMetaBar = document.getElementById('csvMetaBar');
  const zoomSlider = document.getElementById('zoomSlider');
  const hoverReadout = document.getElementById('hoverReadout');
  const copyIssueBtn = document.getElementById('copyIssueBtn');

  let parsedRows = [];
  let parsedMeta = {};
  let hoverIdx = -1;

  function parseJumpvexCsv(text) {
    const lines = text.split(/\r?\n/);
    const meta = { app: 'Unknown', device: 'Unknown', mode: 'Unknown' };
    let headerIdx = -1;
    let cols = [];

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line) continue;
      if (line.startsWith('#')) {
        if (line.includes('App:')) meta.app = line.split('App:')[1].trim();
        if (line.includes('Device:')) meta.device = line.split('Device:')[1].trim();
        if (line.includes('Mode:')) meta.mode = line.split('Mode:')[1].trim();
        continue;
      }
      if (headerIdx === -1 && line.includes('ts_ms')) {
        headerIdx = i;
        cols = line.split(',').map(c => c.trim());
        break;
      }
    }
    if (headerIdx === -1) return { meta, rows: [] };

    const colMap = {};
    cols.forEach((c, idx) => { colMap[c] = idx; });
    const rows = [];
    for (let i = headerIdx + 1; i < lines.length; i++) {
      const line = lines[i].trim();
      if (!line || line.startsWith('#')) continue;
      const parts = line.split(',');
      if (parts.length < 8) continue;
      const getNum = (name, def = 0) => {
        const idx = colMap[name];
        if (idx === undefined) return def;
        const v = parseFloat(parts[idx]);
        return Number.isFinite(v) ? v : def;
      };
      const moveIdx = colMap['move'];
      rows.push({
        ts: getNum('ts_ms'),
        fps: getNum('fps'),
        aiUs: getNum('ai_us'),
        heapMb: getNum('heap_mb'),
        jumps: getNum('jumps'),
        airborne: getNum('airborne'),
        move: moveIdx !== undefined ? (parts[moveIdx] || 'Regular').trim() : 'Regular',
        form: getNum('form_score'),
        bpm: getNum('bpm'),
        sync: getNum('sync_pct'),
        ankSep: getNum('ank_sep'),
        lPitch: getNum('l_pitch'),
        rPitch: getNum('r_pitch')
      });
    }
    return { meta, rows };
  }

  function median(arr) {
    if (!arr.length) return 0;
    const s = [...arr].sort((a, b) => a - b);
    const mid = Math.floor(s.length / 2);
    return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
  }

  function renderMetaChips() {
    if (!parsedRows.length) {
      csvMetaBar.innerHTML = '';
      copyIssueBtn.disabled = true;
      return;
    }
    const last = parsedRows[parsedRows.length - 1];
    const durSec = Math.max(0.1, (last.ts - parsedRows[0].ts) / 1000);
    const medCpu = Math.round(median(parsedRows.map(r => r.aiUs)));
    const medHeap = Math.round(median(parsedRows.map(r => r.heapMb)));
    const medFps = Math.round(median(parsedRows.map(r => r.fps)));
    const avgForm = Math.round(parsedRows.reduce((a, b) => a + b.form, 0) / parsedRows.length);
    const moves = [...new Set(parsedRows.map(r => r.move))].join(', ');

    csvMetaBar.innerHTML = `
      <div class="meta-chip mono">Device: <strong>${parsedMeta.device}</strong></div>
      <div class="meta-chip mono">Frames: <strong>${parsedRows.length}</strong> (${durSec.toFixed(1)}s @ ${medFps} FPS)</div>
      <div class="meta-chip mono">Total Jumps: <strong>${last.jumps}</strong></div>
      <div class="meta-chip mono">CPU Median: <strong>${medCpu} µs</strong></div>
      <div class="meta-chip mono">Heap Median: <strong>${medHeap} MB</strong></div>
      <div class="meta-chip mono">Avg Form: <strong>${avgForm}%</strong></div>
      <div class="meta-chip mono">Moves: <strong>${moves}</strong></div>
    `;
    copyIssueBtn.disabled = false;
  }

  function drawOscilloscope() {
    const W = oscCanvas.width;
    const H = oscCanvas.height;
    oscCtx.clearRect(0, 0, W, H);
    oscCtx.fillStyle = '#070b13';
    oscCtx.fillRect(0, 0, W, H);

    // Subtle grid
    oscCtx.strokeStyle = '#141e30';
    oscCtx.lineWidth = 1;
    for (let x = 0; x < W; x += 80) {
      oscCtx.beginPath(); oscCtx.moveTo(x, 0); oscCtx.lineTo(x, H); oscCtx.stroke();
    }
    [145, 290].forEach(y => {
      oscCtx.strokeStyle = '#1e293b';
      oscCtx.beginPath(); oscCtx.moveTo(0, y); oscCtx.lineTo(W, y); oscCtx.stroke();
    });

    if (!parsedRows.length) {
      oscCtx.fillStyle = '#64748b';
      oscCtx.font = '600 16px ui-monospace, monospace';
      oscCtx.textAlign = 'center';
      oscCtx.fillText('Drop a Jumpvex CSV or click "Load Real Redmi Note 9 Sample" above', W / 2, H / 2);
      return;
    }

    const zoom = parseFloat(zoomSlider.value) || 1;
    const visibleCount = Math.max(20, Math.floor(parsedRows.length / zoom));
    const slice = parsedRows.slice(0, visibleCount);
    const padL = 54, padR = 18;
    const plotW = W - padL - padR;
    const xAt = idx => padL + (idx / Math.max(1, slice.length - 1)) * plotW;

    // Compute rolling 1.5s Step/Rope Speed (SPM) across parsedRows
    for (let i = 0; i < parsedRows.length; i++) {
      let j0 = i;
      while (j0 > 0 && (parsedRows[i].ts - parsedRows[j0].ts) < 1500) j0--;
      const dtSec = (parsedRows[i].ts - parsedRows[j0].ts) / 1000;
      const dJumps = parsedRows[i].jumps - parsedRows[j0].jumps;
      parsedRows[i].spm = dtSec >= 0.35 && dJumps > 0 ? Math.round((dJumps / dtSec) * 60) : 0;
    }

    // Band Labels
    oscCtx.font = '700 11px ui-monospace, monospace';
    oscCtx.textAlign = 'left';
    oscCtx.fillStyle = '#2ee59d';
    oscCtx.fillText('TRACK 1: STEP / ROPE SPEED (SPM) & FORM SCORE (%)', 12, 18);
    oscCtx.fillStyle = '#f59e0b';
    oscCtx.fillText('TRACK 2: FOOT-TRIANGLE (ANKLE SEP & FOOT PITCH)', 12, 163);
    oscCtx.fillStyle = '#fb7185';
    oscCtx.fillText('TRACK 3: ENGINE LATENCY (µs) & HEAP (MB)', 12, 308);

    // Track 1 (y: 28..136): Airborne highlight bars + Step/Rope Speed curve + Form curve
    const maxSpm = Math.max(220, ...slice.map(r => r.spm || 0));
    for (let i = 0; i < slice.length; i++) {
      const r = slice[i];
      const x = xAt(i);
      if (r.airborne) {
        oscCtx.fillStyle = 'rgba(46, 229, 157, 0.14)';
        oscCtx.fillRect(x - 2, 28, Math.max(4, plotW / slice.length), 108);
      }
      if (i > 0 && r.jumps > slice[i - 1].jumps) {
        oscCtx.fillStyle = '#2ee59d';
        oscCtx.beginPath(); oscCtx.arc(x, 36, 3.8, 0, Math.PI * 2); oscCtx.fill();
      }
    }
    oscCtx.strokeStyle = '#2ee59d';
    oscCtx.lineWidth = 2.2;
    oscCtx.beginPath();
    slice.forEach((r, i) => {
      const x = xAt(i);
      const y = 134 - (Math.min(maxSpm, Math.max(0, r.spm || 0)) / maxSpm) * 94;
      if (i === 0) oscCtx.moveTo(x, y); else oscCtx.lineTo(x, y);
    });
    oscCtx.stroke();

    oscCtx.strokeStyle = '#38bdf8';
    oscCtx.lineWidth = 1.7;
    oscCtx.beginPath();
    slice.forEach((r, i) => {
      const x = xAt(i);
      const y = 134 - (Math.min(100, Math.max(0, r.form)) / 100) * 96;
      if (i === 0) oscCtx.moveTo(x, y); else oscCtx.lineTo(x, y);
    });
    oscCtx.stroke();

    // Track 2 (y: 174..280): Ankle Sep + Left Foot Pitch
    oscCtx.strokeStyle = '#f59e0b';
    oscCtx.lineWidth = 2;
    oscCtx.beginPath();
    slice.forEach((r, i) => {
      const x = xAt(i);
      const y = 274 - Math.min(1, Math.max(0, r.ankSep / 0.8)) * 90;
      if (i === 0) oscCtx.moveTo(x, y); else oscCtx.lineTo(x, y);
    });
    oscCtx.stroke();

    oscCtx.strokeStyle = 'rgba(46, 229, 157, 0.75)';
    oscCtx.lineWidth = 1.6;
    oscCtx.beginPath();
    slice.forEach((r, i) => {
      const x = xAt(i);
      const y = 228 - Math.max(-0.5, Math.min(0.5, r.lPitch)) * 80;
      if (i === 0) oscCtx.moveTo(x, y); else oscCtx.lineTo(x, y);
    });
    oscCtx.stroke();

    // Track 3 (y: 318..416): AI CPU us
    const maxUs = Math.max(200, ...slice.map(r => r.aiUs));
    oscCtx.strokeStyle = '#fb7185';
    oscCtx.lineWidth = 2;
    oscCtx.beginPath();
    slice.forEach((r, i) => {
      const x = xAt(i);
      const y = 414 - (r.aiUs / maxUs) * 86;
      if (i === 0) oscCtx.moveTo(x, y); else oscCtx.lineTo(x, y);
    });
    oscCtx.stroke();

    // Hover crosshair
    if (hoverIdx >= 0 && hoverIdx < slice.length) {
      const r = slice[hoverIdx];
      const x = xAt(hoverIdx);
      oscCtx.strokeStyle = 'rgba(248, 250, 252, 0.55)';
      oscCtx.setLineDash([4, 4]);
      oscCtx.beginPath(); oscCtx.moveTo(x, 0); oscCtx.lineTo(x, H); oscCtx.stroke();
      oscCtx.setLineDash([]);
      hoverReadout.textContent =
        `t=${(r.ts / 1000).toFixed(2)}s | Jumps:${r.jumps} | Speed:${r.spm || 0} SPM | ${r.move} | Form:${r.form}% | CPU:${r.aiUs}µs`;
    }
  }

  function loadCsvText(text) {
    const res = parseJumpvexCsv(text);
    parsedMeta = res.meta;
    parsedRows = res.rows;
    hoverIdx = -1;
    renderMetaChips();
    drawOscilloscope();
  }

  async function loadSampleCsv() {
    try {
      const resp = await fetch('sample_redmi_note9.csv');
      if (!resp.ok) throw new Error('Sample fetch failed');
      loadCsvText(await resp.text());
    } catch (e) {
      hoverReadout.textContent = 'Could not load sample CSV: ' + e.message;
    }
  }

  csvFileInput.addEventListener('change', e => {
    const file = e.target.files && e.target.files[0];
    if (!file) return;
    file.text().then(loadCsvText);
  });
  dropzone.addEventListener('dragover', e => { e.preventDefault(); dropzone.classList.add('dragover'); });
  dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));
  dropzone.addEventListener('drop', e => {
    e.preventDefault();
    dropzone.classList.remove('dragover');
    const file = e.dataTransfer.files && e.dataTransfer.files[0];
    if (file) file.text().then(loadCsvText);
  });
  loadSampleBtn.addEventListener('click', loadSampleCsv);
  zoomSlider.addEventListener('input', drawOscilloscope);

  oscCanvas.addEventListener('mousemove', e => {
    if (!parsedRows.length) return;
    const rect = oscCanvas.getBoundingClientRect();
    const relX = ((e.clientX - rect.left) / rect.width) * oscCanvas.width;
    const zoom = parseFloat(zoomSlider.value) || 1;
    const visibleCount = Math.max(20, Math.floor(parsedRows.length / zoom));
    const idx = Math.round(((relX - 54) / (oscCanvas.width - 72)) * (visibleCount - 1));
    hoverIdx = Math.max(0, Math.min(visibleCount - 1, idx));
    drawOscilloscope();
  });

  copyIssueBtn.addEventListener('click', () => {
    if (!parsedRows.length) return;
    const last = parsedRows[parsedRows.length - 1];
    const medCpu = Math.round(median(parsedRows.map(r => r.aiUs)));
    const medHeap = Math.round(median(parsedRows.map(r => r.heapMb)));
    const medFps = Math.round(median(parsedRows.map(r => r.fps)));
    const md = [
      '### Jumpvex Telemetry Lab Summary',
      `- **App:** ${parsedMeta.app}`,
      `- **Device:** ${parsedMeta.device}`,
      `- **Mode:** ${parsedMeta.mode}`,
      `- **Frames / FPS:** ${parsedRows.length} frames (${medFps} FPS median)`,
      `- **Total Jumps:** ${last.jumps}`,
      `- **Engine CPU Median:** ${medCpu} µs/frame`,
      `- **JVM Heap Median:** ${medHeap} MB`
    ].join('\n');
    navigator.clipboard.writeText(md).then(() => {
      copyIssueBtn.textContent = '✅ Copied Markdown Summary!';
      setTimeout(() => { copyIssueBtn.textContent = '📋 Copy Summary for GitHub Issue'; }, 2200);
    });
  });

  // Auto-load real Redmi Note 9 sample on startup!
  loadSampleCsv();

  // =========================================================================
  // 2. INTERACTIVE TRICK KINEMATICS & ±110ms PEAK-ALIGNED SANDBOX
  // =========================================================================
  const TRICKS = [
    {
      id: 'criss_cross', name: 'Criss-Cross', cue: 'Wrist Cross + Whip',
      desc: 'Wrists cross past body midline right at jump apex (0 ms) and already uncross by +220 ms. AI Engine v14 captures the peak crossMetric inside [-110 ms, +110 ms].'
    },
    {
      id: 'double_under', name: 'Double Under', cue: 'Air ≥250ms + 2x Whip',
      desc: 'Higher vertical clearance with 420 ms FPS-independent airborne tracking + 2 rapid high-frequency acoustic rope whips per jump.'
    },
    {
      id: 'triple_under', name: 'Triple Under', cue: 'Air ≥340ms + 3x Whip',
      desc: 'Explosive vertical leap with tucked knees and 3 distinct 2.5–7 kHz rope-pass bursts locked by 100 Hz STFT.'
    },
    {
      id: 'boxer_step', name: 'Boxer Step', cue: 'Alternating Heel-Toe',
      desc: 'Low-impact weight shift alternating left and right foot pitch via the 6-bone Heel-to-Toe Foot-Triangle.'
    },
    {
      id: 'running_man', name: 'Running Man (Shuffle)', cue: '2-Beat Glide + BPM',
      desc: 'Melbourne Shuffle forward-back ankle scissor synced to 60–250 Hz kick-drum BPM with 2-beat anti-walking confirmation.'
    }
  ];

  const trickPillsEl = document.getElementById('trickPills');
  const skelCanvas = document.getElementById('skeletonCanvas');
  const skelCtx = skelCanvas.getContext('2d');
  const phaseSlider = document.getElementById('phaseSlider');
  const phaseLabel = document.getElementById('phaseLabel');
  const trickTitle = document.getElementById('trickTitle');
  const trickDesc = document.getElementById('trickDesc');
  const windowStatus = document.getElementById('windowStatus');
  const sensorCue = document.getElementById('sensorCue');
  const togglePlayBtn = document.getElementById('togglePlayBtn');

  let activeTrick = TRICKS[0];
  let isPlaying = true;
  let phaseMs = 0;

  function renderTrickPills() {
    trickPillsEl.innerHTML = '';
    TRICKS.forEach(tr => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'trick-pill' + (tr.id === activeTrick.id ? ' active' : '');
      btn.textContent = tr.name;
      btn.addEventListener('click', () => {
        activeTrick = tr;
        renderTrickPills();
        updateTrickText();
      });
      trickPillsEl.appendChild(btn);
    });
  }

  function updateTrickText() {
    trickTitle.textContent = activeTrick.name;
    trickDesc.textContent = activeTrick.desc;
    sensorCue.textContent = activeTrick.cue;
  }

  function drawSkeleton(ms) {
    const W = skelCanvas.width, H = skelCanvas.height;
    skelCtx.clearRect(0, 0, W, H);
    skelCtx.fillStyle = '#070b13';
    skelCtx.fillRect(0, 0, W, H);

    const inWindow = Math.abs(ms) <= 110;
    const norm = Math.max(0, 1 - Math.abs(ms) / 210); // 1 at apex (0ms), 0 at ground
    const lift = activeTrick.id === 'triple_under' ? 78 * norm :
                 activeTrick.id === 'double_under' ? 58 * norm :
                 activeTrick.id === 'running_man' ? 14 * norm : 36 * norm;

    // Peak-Aligned Window highlight bar at bottom
    const barX = 40, barW = W - 80, barY = H - 30;
    skelCtx.fillStyle = '#141e30';
    skelCtx.fillRect(barX, barY, barW, 10);
    const mapT = t => barX + ((t + 200) / 460) * barW;
    skelCtx.fillStyle = 'rgba(46, 229, 157, 0.35)';
    skelCtx.fillRect(mapT(-110), barY - 2, mapT(110) - mapT(-110), 14);
    skelCtx.fillStyle = inWindow ? '#2ee59d' : '#f59e0b';
    skelCtx.beginPath(); skelCtx.arc(mapT(ms), barY + 5, 6, 0, Math.PI * 2); skelCtx.fill();

    // Ground line
    const groundY = 308;
    skelCtx.strokeStyle = '#1e293b';
    skelCtx.lineWidth = 2;
    skelCtx.beginPath(); skelCtx.moveTo(40, groundY); skelCtx.lineTo(W - 40, groundY); skelCtx.stroke();

    const cx = W / 2;
    const hipY = 185 - lift;
    const shY = 112 - lift;
    const headY = 76 - lift;

    // Wrists (cross at apex for Criss-Cross!)
    const crossAmt = activeTrick.id === 'criss_cross' ? norm * 96 : 0;
    const lWrist = { x: cx - 54 + crossAmt, y: hipY + 8 - norm * 10 };
    const rWrist = { x: cx + 54 - crossAmt, y: hipY + 8 - norm * 10 };

    // Ankles & Foot-Triangles
    const boxerShift = activeTrick.id === 'boxer_step' ? Math.sin((ms / 200) * Math.PI) * 18 : 0;
    const shuffleSpread = activeTrick.id === 'running_man' ? Math.cos((ms / 200) * Math.PI) * 44 : 0;
    const tuck = (activeTrick.id === 'triple_under' ? 28 : 0) * norm;

    const lAnk = { x: cx - 22 - shuffleSpread, y: groundY - 12 - lift + boxerShift - tuck };
    const rAnk = { x: cx + 22 + shuffleSpread, y: groundY - 12 - lift - boxerShift - tuck };
    const lKnee = { x: cx - 24 - shuffleSpread * 0.4, y: (hipY + lAnk.y) / 2 - tuck * 0.4 };
    const rKnee = { x: cx + 24 + shuffleSpread * 0.4, y: (hipY + rAnk.y) / 2 - tuck * 0.4 };

    const pts = {
      lSh: { x: cx - 34, y: shY }, rSh: { x: cx + 34, y: shY },
      lHip: { x: cx - 22, y: hipY }, rHip: { x: cx + 22, y: hipY },
      lElb: { x: cx - 48 + crossAmt * 0.25, y: (shY + hipY) / 2 + 8 },
      rElb: { x: cx + 48 - crossAmt * 0.25, y: (shY + hipY) / 2 + 8 },
      lWrist, rWrist, lKnee, rKnee, lAnk, rAnk,
      lHeel: { x: lAnk.x - 10, y: lAnk.y + 10 }, lToe: { x: lAnk.x + 16, y: lAnk.y + 10 - (boxerShift > 0 ? 7 : 0) },
      rHeel: { x: rAnk.x - 10, y: rAnk.y + 10 }, rToe: { x: rAnk.x + 16, y: rAnk.y + 10 - (boxerShift < 0 ? 7 : 0) }
    };

    const bone = (a, b, color = '#2ee59d', w = 3) => {
      skelCtx.strokeStyle = color; skelCtx.lineWidth = w;
      skelCtx.beginPath(); skelCtx.moveTo(a.x, a.y); skelCtx.lineTo(b.x, b.y); skelCtx.stroke();
    };

    // Torso, Arms, Legs
    bone(pts.lSh, pts.rSh); bone(pts.lHip, pts.rHip);
    bone(pts.lSh, pts.lHip); bone(pts.rSh, pts.rHip);
    bone(pts.lSh, pts.lElb, '#38bdf8'); bone(pts.lElb, pts.lWrist, '#38bdf8');
    bone(pts.rSh, pts.rElb, '#38bdf8'); bone(pts.rElb, pts.rWrist, '#38bdf8');
    bone(pts.lHip, pts.lKnee); bone(pts.lKnee, pts.lAnk);
    bone(pts.rHip, pts.rKnee); bone(pts.rKnee, pts.rAnk);

    // 6-Bone Heel-to-Toe Foot-Triangle (Gold/Mint highlight!)
    bone(pts.lAnk, pts.lHeel, '#f59e0b', 2.4); bone(pts.lAnk, pts.lToe, '#f59e0b', 2.4); bone(pts.lHeel, pts.lToe, '#f59e0b', 2.4);
    bone(pts.rAnk, pts.rHeel, '#f59e0b', 2.4); bone(pts.rAnk, pts.rToe, '#f59e0b', 2.4); bone(pts.rHeel, pts.rToe, '#f59e0b', 2.4);

    // Head
    skelCtx.strokeStyle = '#f8fafc'; skelCtx.lineWidth = 3;
    skelCtx.beginPath(); skelCtx.arc(cx, headY, 17, 0, Math.PI * 2); skelCtx.stroke();

    // Joints
    Object.values(pts).forEach(p => {
      skelCtx.fillStyle = '#f8fafc';
      skelCtx.beginPath(); skelCtx.arc(p.x, p.y, 3.8, 0, Math.PI * 2); skelCtx.fill();
    });

    // HUD Status Tag inside canvas
    skelCtx.fillStyle = inWindow ? '#2ee59d' : '#94a3b8';
    skelCtx.font = '700 12px ui-monospace, monospace';
    skelCtx.textAlign = 'left';
    skelCtx.fillText(inWindow ? '● PEAK-ALIGNED WINDOW (±110 ms) ACTIVE' : '○ OUTSIDE PEAK WINDOW (POST-LANDING)', 16, 26);

    phaseLabel.textContent = `${ms >= 0 ? '+' : ''}${Math.round(ms)} ms (${inWindow ? 'Inside ±110 ms Window' : 'Ground / Lookahead'})`;
    windowStatus.textContent = inWindow ? '✅ CAPTURED' : '⏳ IDLE';
    windowStatus.style.color = inWindow ? '#2ee59d' : '#f59e0b';
  }

  renderTrickPills();
  updateTrickText();

  phaseSlider.addEventListener('input', () => {
    isPlaying = false;
    togglePlayBtn.textContent = '▶ Play Animation';
    phaseMs = parseFloat(phaseSlider.value);
    drawSkeleton(phaseMs);
  });
  togglePlayBtn.addEventListener('click', () => {
    isPlaying = !isPlaying;
    togglePlayBtn.textContent = isPlaying ? '⏸ Pause Animation' : '▶ Play Animation';
  });

  function animateLoop() {
    if (isPlaying) {
      phaseMs += 4;
      if (phaseMs > 260) phaseMs = -200;
      phaseSlider.value = Math.round(phaseMs);
      drawSkeleton(phaseMs);
    }
    requestAnimationFrame(animateLoop);
  }
  requestAnimationFrame(animateLoop);

  // =========================================================================
  // 3. ROPE PHYSICS MET & FRAMING CALCULATOR + LIVE GITHUB RELEASE
  // =========================================================================
  const weightSlider = document.getElementById('weightSlider');
  const ropeSelect = document.getElementById('ropeSelect');
  const rpmSlider = document.getElementById('rpmSlider');
  const durSlider = document.getElementById('durSlider');
  const distSlider = document.getElementById('distSlider');

  function updatePhysics() {
    const kg = parseFloat(weightSlider.value);
    const mult = parseFloat(ropeSelect.value);
    const rpm = parseFloat(rpmSlider.value);
    const mins = parseFloat(durSlider.value);
    const dist = parseFloat(distSlider.value);

    document.getElementById('weightVal').textContent = `${kg} kg`;
    document.getElementById('rpmVal').textContent = `${rpm} RPM`;
    document.getElementById('durVal').textContent = `${mins} min`;
    document.getElementById('distVal').textContent = `${dist.toFixed(1)} m`;

    // Matches MetricsTracker.kt ACSM intensity-scaled MET
    const baseMet = rpm < 100 ? 8.8 : rpm < 120 ? 11.8 : rpm < 150 ? 12.3 : 13.5;
    const effMet = baseMet * mult;
    const kcal = Math.round((effMet * 3.5 * kg / 200) * mins);
    const jumps = Math.round(rpm * mins);

    document.getElementById('outKcal').textContent = `${kcal.toLocaleString()} kcal`;
    document.getElementById('outMet').textContent = `${effMet.toFixed(2)} MET`;
    document.getElementById('outJumps').textContent = jumps.toLocaleString();

    const frameEl = document.getElementById('outFrame');
    if (dist < 1.8) {
      frameEl.textContent = '⚠️ Feet May Clip';
      frameEl.style.color = '#fb7185';
    } else if (dist <= 3.3) {
      frameEl.textContent = '✅ Optimal Full-Body';
      frameEl.style.color = '#2ee59d';
    } else {
      frameEl.textContent = '🟡 Small Subject';
      frameEl.style.color = '#f59e0b';
    }
  }

  [weightSlider, ropeSelect, rpmSlider, durSlider, distSlider].forEach(el => {
    el.addEventListener('input', updatePhysics);
  });
  updatePhysics();

  // Fetch latest release metadata from GitHub API
  fetch('https://api.github.com/repos/iganramdani/Jumpvex/releases/latest')
    .then(r => (r.ok ? r.json() : null))
    .then(rel => {
      if (!rel || !rel.tag_name) return;
      document.getElementById('navVersion').textContent = rel.tag_name;
      const assets = rel.assets || [];
      const releaseApk = assets.find(a => /release.*\.apk$/i.test(a.name)) || assets.find(a => /\.apk$/i.test(a.name));
      const sizeMb = releaseApk ? ` (${(releaseApk.size / 1048576).toFixed(1)} MB)` : '';
      document.getElementById('releaseTitle').textContent = `Jumpvex ${rel.tag_name}${sizeMb}`;
      if (releaseApk && releaseApk.browser_download_url) {
        document.getElementById('releaseApkBtn').href = releaseApk.browser_download_url;
        document.getElementById('navDownloadBtn').href = releaseApk.browser_download_url;
      }
    })
    .catch(() => {});
})();
