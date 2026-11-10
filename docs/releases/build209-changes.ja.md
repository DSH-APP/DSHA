# DSHA 0.2.1-rc.3-20261029.1845-dsh0.2.1-rc.3 / build209

- アプリ名を DSH-A-Su（Sunset Mod）に、applicationId を io.github.sunsetrne.dsha に変更しました。元の dsh.android.client と並行インストールでき、データは完全に分離され、旧データは上書きされません。
- リリース署名を新規に作成しました。従来の E7E3 証明書は使わず、Mod 独自のリリース証明書に切り替えています。verifyPublishCertificate は新しい証明書と新しいパッケージ名を検証し、期待ハッシュは ci/release-identity.json に記録しています。
- 上島（通知カプセル）版を正式パッケージに統合しました。個別のビルドや独自のパッケージ名は不要になりました。
- Android 10 以前のサポートを終了しました。low / GeckoView 互換ラインを削除し、最低要件を Android 11（API 30）、arm64-v8a のみとしています。
- ユーザー補助の宣言を全バリアント共通にしました。main manifest に置き、packageNames を限定せず、flavor による上書きも廃止しています。ラベルと説明は「DSH-A-Su 画面操作」に更新して簡潔にしました。
- 言語は zh、zh-Hant、en、ru、ja、ko、ar、pt、la、fr の 10 種類を維持します。
- fr、pt、ru、ar、la の画面の短いラベルをさらに簡潔にし、表示面積を抑えました。
- ラテン語（la）の「次へ」の壊れた文言を修正しました。
- 固定ビルド入力は公開リポジトリのプレリリース Release アセットに変更し、SHA-256 で固定しました。公開リポジトリから完全なビルド入力を復元できます（[固定ビルド入力の文書](../../docs/fixed-build-inputs.md)を参照）。
- パッケージ名と証明書に関わるリリースツールと CI アサーションも更新しました。ビルドとゲートの結果はリポジトリの受領記録に従います。
