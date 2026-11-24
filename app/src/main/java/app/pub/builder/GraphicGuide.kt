package app.pub.builder

import app.pub.core.GraphicAsset

object GraphicGuide {
    val CONTRACT = """
        GRAPHICAL ASSETS: You can create real scalable illustrations/icons/schematics as vector geometry,
        or request generated PNG illustrations. Do not replace requested graphics with ASCII, emoji diagrams,
        placeholder text, or a claim that PUB cannot draw. Use the following native assets.
        Optional package field: assets:{assetId:definition,...}. Max8 assets (keep one free for the launcher icon), max2 generated images.
        Launcher icon: PUB designs it in a separate step, so do not draw one or add an icon field to a new app. When revising an app, return its icon field and that asset exactly as they are.
        Asset/shape IDs use 1..40 ASCII letters,digits,_,-. Store assets in the package, never in JavaScript/storage.save.
        Use {type:'graphic',asset:'guitar',description:'Guitar strings',highlight:['string-0']} in the UI.
        Each dispatch can change highlight IDs; the host accents matching vector shapes. Max4 image/graphic nodes total.
        Vector definition: {type:'vector',width:320,height:400,shapes:[...]}. Width/height>0..1024; max128 shapes.
        Every shape has unique id,type; optional fill/stroke: '#RRGGBB', '#AARRGGBB', transparent, or a theme colour name:
        ${GraphicAsset.THEME_COLORS.filter { it != "transparent" }.joinToString(",")}.
        Theme colour names are the Material colour roles of the user's style guide and follow its colours and light or dark mode,
        also when the user changes them later (the device context gives the current styleGuide and uiTheme); hex colours never change.
        strokeWidth0..20. Coordinates must be finite in -1024..2048. Give explicit fill/stroke for predictable results.
        rect: {id,type:'rect',x,y,width,height,rx?:cornerRadius}; ellipse:{id,type:'ellipse',cx,cy,rx,ry};
        line:{id,type:'line',x1,y1,x2,y2}; text:{id,type:'text',x,y,text:'<=40 chars',size:1..64};
        path:{id,type:'path',commands:[['M',x,y],['L',x,y],['C',cx1,cy1,cx2,cy2,x,y],['Q',cx,cy,x,y],['Z']]}.
        Path max64 commands, first M. No SVG/XML/HTML/CSS/URLs or arbitrary properties.
        Draw layers in order. Give each changing part its own shape ID; highlight IDs must refer to actual shapes.
        For instrument tuners prefer vectors: draw the body/neck/strings with curves, label strings and highlight the detected string
        after a confident pitch. Clear highlights on silence, unreliable pitch and stop. Pitch estimates the nearest string;
        it cannot prove which physical string was plucked when pitches coincide. Do not highlight only a manual target and call it detected.
        Prefer vectors for icons, diagrams and simple illustrations. Request a generated PNG only when raster artwork is specifically useful and host capability allows it.
        Generated image definition: {type:'image',prompt:'specific visual description, <=2000 chars'}.
        Host invokes image generation with the connected account and stores the PNG locally. Never output base64,data,URLs or pretend pixels.
        Static images do not have highlightable internal parts: use vectors for interactive diagrams.
        Preserve existing image ID and exact prompt to reuse its local PNG during an edit; change its prompt to regenerate.
        Unchanged image bytes are never sent back to the model. If a requested image fails, host reports failure rather than silently replacing it.
    """.trimIndent()
}
