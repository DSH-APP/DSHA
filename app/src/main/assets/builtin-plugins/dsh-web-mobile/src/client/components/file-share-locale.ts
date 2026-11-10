/**
 * `dshWebMobileNavFileShare` namespace: file-share copy, registered by
 * installFileShare (effects/file-share.ts) through ctx.locale.register — kept
 * out of i18n/locales.ts so the feature owns its dictionary end to end.
 */
export const FILE_SHARE_NS = 'dshWebMobileNavFileShare'

/** Simplified Chinese dictionary (the key-set source of truth). */
export const fileShareZh = {
  'shareFile': '分享「{name}」',
  'downloadFile': '下载「{name}」',
  'sharing': '正在准备文件…',
  'downloadedUnsupported': '当前环境不支持直接分享；已开始下载，请在系统保存位置确认',
  'downloadedTooLarge': '文件超过 50 MB；已开始下载，请在系统保存位置确认',
  'downloadedShareFailed': '分享未能完成；已开始下载，请在系统保存位置确认',
  'errorTooLarge': '文件太大（超过 200 MB），无法在手机端分享或下载',
  'errorNotFound': '文件不存在或已被移动',
  'errorGeneric': '分享失败：{message}',
} as const

/** Key union of the namespace. */
export type FileShareKey = keyof typeof fileShareZh

/** English dictionary, key-identical to the Chinese source of truth. */
export const fileShareEn: Record<FileShareKey, string> = {
  'shareFile': 'Share "{name}"',
  'downloadFile': 'Download "{name}"',
  'sharing': 'Preparing file…',
  'downloadedUnsupported': 'Direct sharing is unavailable here; a download has started — confirm it at the location your system shows',
  'downloadedTooLarge': 'The file is over 50 MB; a download has started — confirm it at the location your system shows',
  'downloadedShareFailed': 'Sharing did not finish; a download has started — confirm it at the location your system shows',
  'errorTooLarge': 'The file is too large (over 200 MB) to share or download on mobile',
  'errorNotFound': 'The file no longer exists or was moved',
  'errorGeneric': 'Share failed: {message}',
}

declare module '@deepseek-ai/dsh-client-ui-slots' {
  interface LocaleNamespaceMap {
    /** File-share controls copy (files tree rows + preview header). */
    'dshWebMobileNavFileShare': FileShareKey
  }
}

// DSHA_MOBILE_LOCALES_V1: generated from tools/mobile-locales.json; do not edit here.
export const fileShareLocales: Record<string, Record<FileShareKey, string>> = { zh: fileShareZh, en: fileShareEn, ...{
  "zh-Hant": {
    "shareFile": "分享「{name}」",
    "downloadFile": "下載「{name}」",
    "sharing": "正在準備檔案…",
    "downloadedUnsupported": "此環境不支援直接分享；已開始下載，請在系統顯示的儲存位置確認",
    "downloadedTooLarge": "檔案超過 50 MB；已開始下載，請在系統顯示的儲存位置確認",
    "downloadedShareFailed": "分享未能完成；已開始下載，請在系統顯示的儲存位置確認",
    "errorTooLarge": "檔案太大（超過 200 MB），無法在手機上分享或下載",
    "errorNotFound": "檔案不存在或已被移動",
    "errorGeneric": "分享失敗：{message}"
  },
  "ru": {
    "shareFile": "Поделиться файлом «{name}»",
    "downloadFile": "Скачать «{name}»",
    "sharing": "Подготовка файла…",
    "downloadedUnsupported": "Прямая отправка недоступна; загрузка началась. Проверьте место сохранения, указанное системой",
    "downloadedTooLarge": "Размер файла превышает 50 MB; загрузка началась. Проверьте место сохранения, указанное системой",
    "downloadedShareFailed": "Отправка не завершена; загрузка началась. Проверьте место сохранения, указанное системой",
    "errorTooLarge": "Файл слишком большой (более 200 MB) для отправки или скачивания на телефоне",
    "errorNotFound": "Файл больше не существует или был перемещён",
    "errorGeneric": "Не удалось поделиться: {message}"
  },
  "ja": {
    "shareFile": "「{name}」を共有",
    "downloadFile": "「{name}」をダウンロード",
    "sharing": "ファイルを準備中…",
    "downloadedUnsupported": "この環境では直接共有できないため、ダウンロードを開始しました。システムに表示された保存先で確認してください",
    "downloadedTooLarge": "ファイルが 50 MB を超えるため、ダウンロードを開始しました。システムに表示された保存先で確認してください",
    "downloadedShareFailed": "共有を完了できなかったため、ダウンロードを開始しました。システムに表示された保存先で確認してください",
    "errorTooLarge": "ファイルが大きすぎる（200 MB 超）ため、スマートフォンでは共有またはダウンロードできません",
    "errorNotFound": "ファイルが存在しないか、移動されています",
    "errorGeneric": "共有に失敗しました：{message}"
  },
  "ko": {
    "shareFile": "‘{name}’ 공유",
    "downloadFile": "‘{name}’ 다운로드",
    "sharing": "파일 준비 중…",
    "downloadedUnsupported": "여기서는 직접 공유할 수 없어 다운로드를 시작했습니다. 시스템에 표시된 저장 위치를 확인하세요",
    "downloadedTooLarge": "파일이 50 MB를 초과하여 다운로드를 시작했습니다. 시스템에 표시된 저장 위치를 확인하세요",
    "downloadedShareFailed": "공유를 완료하지 못해 다운로드를 시작했습니다. 시스템에 표시된 저장 위치를 확인하세요",
    "errorTooLarge": "파일이 너무 커서(200 MB 초과) 휴대전화에서 공유하거나 다운로드할 수 없습니다",
    "errorNotFound": "파일이 없거나 이동되었습니다",
    "errorGeneric": "공유 실패: {message}"
  },
  "ar": {
    "shareFile": "مشاركة «{name}»",
    "downloadFile": "تنزيل «{name}»",
    "sharing": "جارٍ تجهيز الملف…",
    "downloadedUnsupported": "المشاركة المباشرة غير متاحة هنا؛ بدأ التنزيل. تحقّق من موقع الحفظ الذي يعرضه النظام",
    "downloadedTooLarge": "يتجاوز حجم الملف 50 MB؛ بدأ التنزيل. تحقّق من موقع الحفظ الذي يعرضه النظام",
    "downloadedShareFailed": "لم تكتمل المشاركة؛ بدأ التنزيل. تحقّق من موقع الحفظ الذي يعرضه النظام",
    "errorTooLarge": "الملف كبير جدًا (أكثر من 200 MB) ولا يمكن مشاركته أو تنزيله على الهاتف",
    "errorNotFound": "الملف لم يعد موجودًا أو تم نقله",
    "errorGeneric": "فشلت المشاركة: {message}"
  },
  "pt": {
    "shareFile": "Compartilhar “{name}”",
    "downloadFile": "Baixar “{name}”",
    "sharing": "Preparando arquivo…",
    "downloadedUnsupported": "O compartilhamento direto não está disponível aqui; o download foi iniciado. Confira o local de salvamento indicado pelo sistema",
    "downloadedTooLarge": "O arquivo tem mais de 50 MB; o download foi iniciado. Confira o local de salvamento indicado pelo sistema",
    "downloadedShareFailed": "O compartilhamento não foi concluído; o download foi iniciado. Confira o local de salvamento indicado pelo sistema",
    "errorTooLarge": "O arquivo é grande demais (mais de 200 MB) para compartilhar ou baixar no celular",
    "errorNotFound": "O arquivo não existe mais ou foi movido",
    "errorGeneric": "Falha ao compartilhar: {message}"
  },
  "la": {
    "shareFile": "Communica «{name}»",
    "downloadFile": "Depone «{name}»",
    "sharing": "Fasciculus paratur…",
    "downloadedUnsupported": "Communicatio directa hic non praesto est; depositio incepta est. Confirma in loco quem systema indicat",
    "downloadedTooLarge": "Fasciculus maior est quam 50 MB; depositio incepta est. Confirma in loco quem systema indicat",
    "downloadedShareFailed": "Communicatio non perfecta est; depositio incepta est. Confirma in loco quem systema indicat",
    "errorTooLarge": "Fasciculus nimis magnus est (maior quam 200 MB) ut in telephono communicetur aut deponatur",
    "errorNotFound": "Fasciculus iam non exstat aut motus est",
    "errorGeneric": "Communicatio defecit: {message}"
  },
  "fr": {
    "shareFile": "Partager « {name} »",
    "downloadFile": "Télécharger « {name} »",
    "sharing": "Préparation du fichier…",
    "downloadedUnsupported": "Le partage direct est indisponible ici ; le téléchargement a commencé. Vérifiez l’emplacement indiqué par le système",
    "downloadedTooLarge": "Le fichier dépasse 50 MB ; le téléchargement a commencé. Vérifiez l’emplacement indiqué par le système",
    "downloadedShareFailed": "Le partage n’a pas abouti ; le téléchargement a commencé. Vérifiez l’emplacement indiqué par le système",
    "errorTooLarge": "Le fichier est trop volumineux (plus de 200 MB) pour être partagé ou téléchargé sur mobile",
    "errorNotFound": "Le fichier n’existe plus ou a été déplacé",
    "errorGeneric": "Échec du partage : {message}"
  }
} }

// DSHA_MOBILE_LOCALES_V1: generated from tools/mobile-locales.json; do not edit here.
export const nativeFileActionLocales: Record<string, Record<string, string>> = {
  "zh": {
    "download": "下载",
    "more": "更多文件操作",
    "saveas": "另存为…",
    "share": "分享",
    "gallery": "保存到相册",
    "records": "下载记录",
    "close": "取消",
    "queued": "已加入下载任务，可在下载记录中查看",
    "preparing": "正在准备文件…",
    "sharing": "正在准备分享，完成后将打开系统分享选择器",
    "saving": "正在准备另存为，完成后请选择保存位置",
    "shareLimit": "系统分享上限为 32 MiB；较大文件请使用下载或另存为",
    "failed": "文件操作失败："
  },
  "en": {
    "download": "Download",
    "more": "More file actions",
    "saveas": "Save as…",
    "share": "Share",
    "gallery": "Save to gallery",
    "records": "Downloads",
    "close": "Cancel",
    "queued": "Added to downloads. View progress in Downloads.",
    "preparing": "Preparing file…",
    "sharing": "Preparing to share. The system share picker will open when ready.",
    "saving": "Preparing Save as. Choose a destination when ready.",
    "shareLimit": "System sharing is limited to 32 MiB. Use Download or Save as for larger files.",
    "failed": "File action failed: "
  },
  "zh-Hant": {
    "download": "下載",
    "more": "更多檔案操作",
    "saveas": "另存新檔…",
    "share": "分享",
    "gallery": "儲存到相簿",
    "records": "下載記錄",
    "close": "取消",
    "queued": "已加入下載工作，可在下載記錄中查看",
    "preparing": "正在準備檔案…",
    "sharing": "正在準備分享，完成後將開啟系統分享選擇器",
    "saving": "正在準備另存新檔，完成後請選擇儲存位置",
    "shareLimit": "系統分享上限為 32 MiB；較大檔案請使用下載或另存新檔",
    "failed": "檔案操作失敗："
  },
  "ru": {
    "download": "Скачать",
    "more": "Другие действия с файлом",
    "saveas": "Сохранить как…",
    "share": "Поделиться",
    "gallery": "Сохранить в галерею",
    "records": "Загрузки",
    "close": "Отмена",
    "queued": "Добавлено в загрузки. Ход выполнения доступен в разделе «Загрузки».",
    "preparing": "Подготовка файла…",
    "sharing": "Подготовка к отправке. Затем откроется системное меню отправки.",
    "saving": "Подготовка сохранения. Затем выберите место сохранения.",
    "shareLimit": "Системная отправка ограничена 32 MiB. Для больших файлов используйте «Скачать» или «Сохранить как».",
    "failed": "Не удалось выполнить действие с файлом: "
  },
  "ja": {
    "download": "ダウンロード",
    "more": "その他のファイル操作",
    "saveas": "名前を付けて保存…",
    "share": "共有",
    "gallery": "ギャラリーに保存",
    "records": "ダウンロード履歴",
    "close": "キャンセル",
    "queued": "ダウンロードに追加しました。進行状況はダウンロード履歴で確認できます。",
    "preparing": "ファイルを準備中…",
    "sharing": "共有の準備中です。準備ができるとシステムの共有メニューが開きます。",
    "saving": "保存の準備中です。準備ができたら保存先を選択してください。",
    "shareLimit": "システムで共有できる上限は 32 MiB です。大きなファイルはダウンロードか「名前を付けて保存」を使用してください。",
    "failed": "ファイル操作に失敗しました："
  },
  "ko": {
    "download": "다운로드",
    "more": "기타 파일 작업",
    "saveas": "다른 이름으로 저장…",
    "share": "공유",
    "gallery": "갤러리에 저장",
    "records": "다운로드 기록",
    "close": "취소",
    "queued": "다운로드에 추가했습니다. 다운로드 기록에서 진행 상황을 확인하세요.",
    "preparing": "파일 준비 중…",
    "sharing": "공유를 준비 중입니다. 준비가 끝나면 시스템 공유 선택기가 열립니다.",
    "saving": "저장을 준비 중입니다. 준비가 끝나면 저장 위치를 선택하세요.",
    "shareLimit": "시스템 공유 한도는 32 MiB입니다. 더 큰 파일은 다운로드하거나 다른 이름으로 저장하세요.",
    "failed": "파일 작업 실패: "
  },
  "ar": {
    "download": "تنزيل",
    "more": "المزيد من إجراءات الملف",
    "saveas": "حفظ باسم…",
    "share": "مشاركة",
    "gallery": "حفظ في المعرض",
    "records": "التنزيلات",
    "close": "إلغاء",
    "queued": "أُضيف إلى التنزيلات. يمكنك متابعة التقدم في التنزيلات.",
    "preparing": "جارٍ تجهيز الملف…",
    "sharing": "جارٍ التجهيز للمشاركة. ستُفتح نافذة المشاركة في النظام عند الانتهاء.",
    "saving": "جارٍ التجهيز للحفظ باسم. اختر موقع الحفظ عند الانتهاء.",
    "shareLimit": "حد المشاركة عبر النظام هو 32 MiB. استخدم التنزيل أو الحفظ باسم للملفات الأكبر.",
    "failed": "فشل إجراء الملف: "
  },
  "pt": {
    "download": "Baixar",
    "more": "Mais ações de arquivo",
    "saveas": "Salvar como…",
    "share": "Compartilhar",
    "gallery": "Salvar na galeria",
    "records": "Downloads",
    "close": "Cancelar",
    "queued": "Adicionado aos downloads. Confira o progresso em Downloads.",
    "preparing": "Preparando arquivo…",
    "sharing": "Preparando o compartilhamento. O seletor de compartilhamento do sistema será aberto quando estiver pronto.",
    "saving": "Preparando para salvar como. Escolha um destino quando estiver pronto.",
    "shareLimit": "O compartilhamento pelo sistema é limitado a 32 MiB. Use Baixar ou Salvar como para arquivos maiores.",
    "failed": "Falha na ação de arquivo: "
  },
  "la": {
    "download": "Depone",
    "more": "Plures actiones fasciculi",
    "saveas": "Serva ut…",
    "share": "Communica",
    "gallery": "Serva in pinacotheca",
    "records": "Depositiones",
    "close": "Cancella",
    "queued": "Ad depositiones additum est. Progressum ibi vide.",
    "preparing": "Fasciculus paratur…",
    "sharing": "Communicatio paratur. Cum paratum erit, selector systematis aperietur.",
    "saving": "Servatio paratur. Cum paratum erit, locum elige.",
    "shareLimit": "Limes communicationis per systema est 32 MiB. Fasciculos maiores depone aut «Serva ut» utere.",
    "failed": "Actio fasciculi defecit: "
  },
  "fr": {
    "download": "Télécharger",
    "more": "Autres actions sur le fichier",
    "saveas": "Enregistrer sous…",
    "share": "Partager",
    "gallery": "Enregistrer dans la galerie",
    "records": "Téléchargements",
    "close": "Annuler",
    "queued": "Ajouté aux téléchargements. Consultez la progression dans Téléchargements.",
    "preparing": "Préparation du fichier…",
    "sharing": "Préparation du partage. Le sélecteur de partage du système s’ouvrira une fois le fichier prêt.",
    "saving": "Préparation de l’enregistrement. Choisissez un emplacement lorsque le fichier sera prêt.",
    "shareLimit": "Le partage système est limité à 32 MiB. Utilisez Télécharger ou Enregistrer sous pour les fichiers plus volumineux.",
    "failed": "Échec de l’action sur le fichier : "
  }
}
export function nativeFileCopy(language = document.documentElement.lang) {
  const locale = String(language || 'en').replace(/_/g, '-').toLowerCase();
  const base = locale.split('-')[0];
  const key = base === 'zh'
    ? (/^zh-(?:hant|tw|hk|mo)(?:-|$)/.test(locale) ? 'zh-Hant' : 'zh')
    : (Object.prototype.hasOwnProperty.call(nativeFileActionLocales, base) ? base : 'en');
  return nativeFileActionLocales[key]!;
}
