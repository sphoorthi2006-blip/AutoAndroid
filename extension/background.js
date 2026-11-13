// Clicking the toolbar icon opens (or focuses) the Upscaler page in its own tab.
chrome.action.onClicked.addListener(async () => {
  const url = chrome.runtime.getURL('upscaler.html');
  try {
    const tabs = await chrome.tabs.query({ url });
    if (tabs.length) {
      await chrome.tabs.update(tabs[0].id, { active: true });
      await chrome.windows.update(tabs[0].windowId, { focused: true });
      return;
    }
  } catch (e) { /* fall through and open a new tab */ }
  chrome.tabs.create({ url });
});
