package com.deepseekharness.app.screen;

import com.deepseekharness.app.util.ScreenWindowRegion;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Content-free geometry returned with the same observation; never grants input authority. */
final class ScreenGeometry {
  private ScreenGeometry() {}

  static JSONObject describe(ScreenWindowRegion region) throws JSONException {
    if (region == null) return new JSONObject().put("available", false);
    var exclusions = new JSONArray();
    for (var exclusion : region.exclusions())
      exclusions.put(
          new JSONObject()
              .put("windowId", exclusion.windowId())
              .put("epoch", exclusion.epoch())
              .put("bounds", bounds(exclusion.bounds())));
    var occluders = new JSONArray();
    for (var occluder : region.occluders())
      occluders.put(
          new JSONObject()
              .put("windowId", occluder.windowId())
              .put("kind", occluder.kind())
              .put("bounds", bounds(occluder.bounds())));
    return new JSONObject()
        .put("available", true)
        .put("windowBounds", bounds(region.window()))
        .put("contentBounds", bounds(region.content()))
        .put("obscured", region.obscured())
        .put("excludedRegions", exclusions)
        .put("occludingWindows", occluders);
  }

  static Object bounds(ScreenWindowRegion.Bounds bounds) throws JSONException {
    if (bounds == null) return JSONObject.NULL;
    return new JSONObject()
        .put("left", bounds.left())
        .put("top", bounds.top())
        .put("right", bounds.right())
        .put("bottom", bounds.bottom());
  }
}
