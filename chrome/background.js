chrome.runtime.onInstalled.addListener(() => {
  // Service worker kept minimal. Popup handles most logic.
  console.log("CornerNAS extension installed.");
});
