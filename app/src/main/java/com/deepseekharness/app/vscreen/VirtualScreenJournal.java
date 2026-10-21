package com.deepseekharness.app.vscreen;

import org.json.JSONObject;

/** API23-safe journal contract; its NIO implementation is loaded only by the API30 factory. */
interface VirtualScreenJournal {
  JSONObject load() throws Exception;

  boolean save(long revision, JSONObject value) throws Exception;
}
