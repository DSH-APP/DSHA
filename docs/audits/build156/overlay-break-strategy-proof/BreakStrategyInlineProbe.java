public final class BreakStrategyInlineProbe {
  public static android.text.StaticLayout.Builder layout(android.text.StaticLayout.Builder builder) {
    return builder.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
  }
  public static void text(android.widget.TextView view) {
    view.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
  }
}
