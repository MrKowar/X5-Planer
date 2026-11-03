// X5 Planer — отправка обращений в поддержку на почту разработчика.
// Письма приходят на почту того Google-аккаунта, под которым развёрнут этот скрипт.
// Как подключить — см. support-mailer/README.md

// Должен совпадать с SUPPORT_EMAIL_TOKEN в app/src/main/assets/index.html
const TOKEN = 'x5planer-support-v1';

function doPost(e) {
  try {
    const d = JSON.parse(e.postData.contents);
    if (d.token !== TOKEN) return ContentService.createTextOutput('forbidden');

    const esc = s => String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const when = Utilities.formatDate(new Date(d.timestamp || Date.now()), 'Europe/Moscow', 'dd.MM.yyyy HH:mm');

    const html =
      '<div style="font-family:Arial,sans-serif;max-width:560px">' +
        '<div style="background:linear-gradient(135deg,#1e88e5,#6a1b9a);color:#fff;padding:16px 20px;border-radius:12px 12px 0 0">' +
          '<div style="font-size:18px;font-weight:bold">Новое сообщение в поддержку</div>' +
          '<div style="font-size:13px;opacity:.85">X5 Planer</div>' +
        '</div>' +
        '<div style="border:1px solid #e5e7eb;border-top:none;padding:20px;border-radius:0 0 12px 12px">' +
          '<p style="margin:0 0 6px"><b>От:</b> ' + esc(d.name) + '</p>' +
          '<p style="margin:0 0 6px"><b>Полигон:</b> ' + esc(d.polygon) + '</p>' +
          '<p style="margin:0 0 6px"><b>Версия приложения:</b> ' + esc(d.appVersion) + '</p>' +
          '<p style="margin:0 0 16px"><b>Время (МСК):</b> ' + when + '</p>' +
          '<div style="background:#f3f6fb;padding:14px;border-radius:10px;white-space:pre-wrap;font-size:15px">' + esc(d.text) + '</div>' +
          '<p style="color:#64748b;font-size:12px;margin-top:16px">Ответить можно в приложении: вкладка «Тикеты 🛠️».</p>' +
        '</div>' +
      '</div>';

    MailApp.sendEmail({
      to: Session.getEffectiveUser().getEmail(),
      subject: 'X5 Planer · Поддержка · ' + d.name,
      htmlBody: html,
      name: 'X5 Planer Поддержка'
    });
    return ContentService.createTextOutput('ok');
  } catch (err) {
    return ContentService.createTextOutput('error: ' + err);
  }
}
