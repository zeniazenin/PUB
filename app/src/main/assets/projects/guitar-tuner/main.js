"use strict";
// The project owns tuning logic and UI. The host owns hardware and grants.
var state = { detected: -1, listening: false, dropD: false, manual: -1, hz: 0, cents: 0, note: "--", status: "Ready when you are", reference: 440, confidence: 0 };
var history = [];
function text(value, style) { return {type: "text", text: value, style: style || "body"}; }
function render() {
  var names = state.dropD ? ["D2", "A2", "D3", "G3", "B3", "E4"] : ["E2", "A2", "D3", "G3", "B3", "E4"];
  var options = [{label: "Auto", action: "string:-1", selected: state.manual === -1}];
  for (var i = 0; i < names.length; i++) options.push({label: names[i], action: "string:" + i, selected: state.manual === i});
  return {type: "column", children: [
    text("GUITAR TUNER", "eyebrow"),
    text(state.note, "display"),
    {type: "graphic", asset: "guitar", description: "Guitar strings 6 to 1. " + (state.detected >= 0 ? "Detected " + names[state.detected] + " by pitch" : "No confident string detected"), highlight: state.detected >= 0 ? ["string" + state.detected] : []},
    text(state.hz ? state.hz.toFixed(2) + " Hz" : "Pluck one string", "subtitle"),
    {type: "meter", value: Math.max(-50, Math.min(50, state.cents)), active: state.hz > 0},
    text(state.hz ? ((state.cents > 0 ? "+" : "") + state.cents.toFixed(1) + " cents") : "-- cents", "subtitle"),
    text(state.status, "body"),
    {type: "choices", options: options},
    {type: "choices", options: [
      {label: "Standard", action: "standard", selected: !state.dropD},
      {label: "Drop D", action: "drop", selected: state.dropD}
    ]},
    {type: "button", label: state.listening ? "Stop listening" : "Start listening", action: state.listening ? "stop" : "start"},
    text("REFERENCE PITCH", "eyebrow"),
    {type: "choices", options: [
      {label: "−1 Hz", action: "refDown"},
      {label: "A4 · " + state.reference + " Hz", action: "refReset", selected: true},
      {label: "+1 Hz", action: "refUp"}
    ]}
  ]};
}
function dispatch(json) {
  var event = JSON.parse(json), effects = [];
  if (event.type === "init" && event.settings) {
    state.dropD = !!event.settings.dropD;
    state.reference = Math.max(430, Math.min(450, Number(event.settings.reference) || 440));
  }
  if (event.type === "action") {
    if (event.action === "start") effects.push({type: "microphone.start"});
    if (event.action === "stop") effects.push({type: "microphone.stop"});
    if (event.action === "drop" || event.action === "standard") { state.dropD = event.action === "drop"; history = []; state.hz = 0; state.note = "--"; state.detected = -1; }
    if (event.action.indexOf("string:") === 0) { state.manual = Number(event.action.split(":")[1]); history = []; }
    if (event.action === "refDown") state.reference = Math.max(430, state.reference - 1);
    if (event.action === "refUp") state.reference = Math.min(450, state.reference + 1);
    if (event.action === "refReset") state.reference = 440;
    if (["drop", "standard", "refDown", "refUp", "refReset"].indexOf(event.action) >= 0) effects.push({type: "settings.save", dropD: state.dropD, reference: state.reference});
  }
  if (event.type === "status") {
    state.listening = !!event.listening;
    state.status = event.message;
    if (!state.listening) { state.hz = 0; state.cents = 0; state.note = "--"; state.detected = -1; history = []; }
  }
  if (event.type === "pitch" && state.listening) {
    if (event.hz > 0 && event.confidence >= 0.85) {
      if (history.length && Math.abs(1200 * Math.log(event.hz / history[history.length - 1]) / Math.LN2) > 80) history = [];
      history.push(event.hz);
      if (history.length > 3) history.shift();
      var sorted = history.slice().sort(function(a,b) { return a-b; });
      state.hz = sorted[Math.floor(sorted.length / 2)];
      var midi = state.dropD ? [38,45,50,55,59,64] : [40,45,50,55,59,64];
      var names = state.dropD ? ["D2","A2","D3","G3","B3","E4"] : ["E2","A2","D3","G3","B3","E4"];
      var index = state.manual, best = Infinity;
      for (var n = 0; n < midi.length; n++) {
        var distance = Math.abs(1200 * Math.log(state.hz / (state.reference * Math.pow(2,(midi[n]-69)/12))) / Math.LN2);
        if (distance < best) { best = distance; state.detected = n; }
      }
      if (index < 0) index = state.detected;
      state.cents = 1200 * Math.log(state.hz / (state.reference * Math.pow(2,(midi[index]-69)/12))) / Math.LN2;
      state.note = names[index];
      state.status = Math.abs(state.cents) <= 5 ? "In tune" : state.cents < 0 ? "Flat · tighten the string" : "Sharp · loosen the string";
    } else {
      state.hz = 0; state.cents = 0; state.note = "--"; state.detected = -1; history = [];
      state.status = event.rms < 0.004 ? "Listening · pluck one string" : "Finding a steady pitch…";
    }
  }
  return JSON.stringify({ui: render(), effects: effects});
}
