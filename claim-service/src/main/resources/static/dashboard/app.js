var claims = [];
var currentUser = null;
var pendingDecision = null;
var currencyFormatter = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });
var dateFormatter = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });

function message(id, text, isError) { var element = document.getElementById(id); element.textContent = text; element.classList.toggle("error", Boolean(isError)); }
function api(url, options) { return fetch(url, options).then(async function (response) { if (!response.ok) { var body = await response.json().catch(function () { return {}; }); throw new Error(body.detail || body.message || "Request could not be completed."); } return response.status === 204 ? null : response.json(); }); }
function cell(text, className) { var element = document.createElement("td"); element.textContent = text; if (className) element.className = className; return element; }

function renderClaims() {
  var query = document.getElementById("claim-search").value.trim().toLowerCase();
  var visible = claims.filter(function (claim) { return [claim.policyNumber, claim.claimantName, claim.claimType, claim.reason, claim.description, claim.status].join(" ").toLowerCase().includes(query); });
  var body = document.getElementById("claims-table-body"); body.replaceChildren();
  visible.forEach(function (claim) {
    var row = document.createElement("tr");
    var primary = cell(currentUser.role === "AGENT" ? claim.claimantName : "Claim " + claim.id.slice(0, 8).toUpperCase(), "claimant");
    var reason = cell(claim.reason, "reason"); reason.title = claim.reason;
    var description = cell(claim.description, "reason"); description.title = claim.description;
    var status = document.createElement("td"); var badge = document.createElement("span"); badge.className = "status-badge " + claim.status; badge.textContent = claim.status; status.appendChild(badge);
    row.append(primary, cell(claim.policyNumber), cell(claim.claimType), reason, description, cell(currencyFormatter.format(claim.estimatedAmount), "money"), status, cell(dateFormatter.format(new Date(claim.submittedAt)), "date"));
    if (currentUser.role === "AGENT") { var actions = document.createElement("td"); if (claim.status === "SUBMITTED") { ["Approve", "Reject"].forEach(function (decision) { var button = document.createElement("button"); button.type = "button"; button.className = "decision-button " + decision.toLowerCase(); button.textContent = decision; button.addEventListener("click", function () { openDecision(claim, decision === "Approve"); }); actions.appendChild(button); }); } row.appendChild(actions); }
    body.appendChild(row);
  });
  document.getElementById("empty-state").hidden = visible.length !== 0;
}
function updateSummary(summary) {
  document.getElementById("total-claims").textContent = summary.totalClaims;
  document.getElementById("submitted-claims").textContent = summary.submittedClaims;
  document.getElementById("settled-claims").textContent = summary.settledClaims;
  document.getElementById("estimated-exposure").textContent = currencyFormatter.format(summary.totalEstimatedAmount);
  ["submitted", "approved", "rejected", "settled"].forEach(function (status) { document.getElementById(status + "-count").textContent = summary[status + "Claims"]; });
}
async function refreshPortal() {
  var refresh = document.getElementById("refresh-button"); refresh.disabled = true;
  try { var results = await Promise.all([api("/claims"), api("/claims/summary")]); claims = results[0]; updateSummary(results[1]); renderClaims(); }
  catch (error) { message("form-message", error.message, true); }
  finally { refresh.disabled = false; }
}
function showPortal(user) {
  currentUser = user; document.getElementById("auth-view").hidden = true; document.getElementById("portal-view").hidden = false;
  var agent = user.role === "AGENT";
  document.getElementById("portal-role").textContent = agent ? "Agent workspace" : "Insured member portal";
  document.getElementById("portal-title").textContent = agent ? "Claims operations" : "My claims";
  document.getElementById("queue-label").textContent = agent ? "Live queue" : "Your coverage";
  document.getElementById("queue-title").textContent = agent ? "Member claim activity" : "Claim activity";
  document.getElementById("exposure-label").textContent = agent ? "Estimated exposure" : "Claim value";
  document.getElementById("claimant-heading").textContent = agent ? "Member" : "Claim";
  document.getElementById("user-name").textContent = user.fullName;
  document.getElementById("submission-panel").hidden = agent;
  document.getElementById("decision-heading").hidden = !agent;
  refreshPortal();
}
function openDecision(claim, approved) { pendingDecision = { claim: claim, approved: approved }; document.getElementById("decision-title").textContent = approved ? "Approve claim" : "Reject claim"; document.getElementById("decision-claim").textContent = claim.claimantName + " - " + claim.policyNumber + " - " + currencyFormatter.format(claim.estimatedAmount); document.getElementById("decision-reason").value = ""; document.getElementById("confirm-decision").textContent = approved ? "Approve claim" : "Reject claim"; document.getElementById("decision-dialog").showModal(); }
function closeDecision() { document.getElementById("decision-dialog").close(); pendingDecision = null; }
async function authenticate(event, endpoint) {
  event.preventDefault(); var form = event.currentTarget; var button = form.querySelector("button"); button.disabled = true; message("auth-message", "", false);
  try { showPortal(await api(endpoint, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(Object.fromEntries(new FormData(form).entries())) })); }
  catch (error) { message("auth-message", error.message, true); } finally { button.disabled = false; }
}
document.querySelectorAll("[data-auth-mode]").forEach(function (button) { button.addEventListener("click", function () { var register = button.dataset.authMode === "register"; document.getElementById("login-form-wrap").hidden = register; document.getElementById("register-form-wrap").hidden = !register; document.querySelectorAll("[data-auth-mode]").forEach(function (tab) { tab.classList.toggle("active", tab === button); }); message("auth-message", "", false); }); });
document.getElementById("login-form").addEventListener("submit", function (event) { authenticate(event, "/api/auth/login"); });
document.getElementById("register-form").addEventListener("submit", function (event) { authenticate(event, "/api/auth/register"); });
document.getElementById("claim-search").addEventListener("input", renderClaims);
document.getElementById("refresh-button").addEventListener("click", refreshPortal);
document.getElementById("logout-button").addEventListener("click", async function () { await fetch("/api/auth/logout", { method: "POST" }); window.location.reload(); });
document.getElementById("claim-form").addEventListener("submit", async function (event) { event.preventDefault(); var form = event.currentTarget; var button = form.querySelector("button"); button.disabled = true; message("form-message", "Submitting your claim...", false); var data = Object.fromEntries(new FormData(form).entries()); data.estimatedAmount = Number(data.estimatedAmount); try { await api("/claims", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) }); form.reset(); message("form-message", "Claim submitted. Its status will update automatically as the workflow completes.", false); await refreshPortal(); } catch (error) { message("form-message", error.message, true); } finally { button.disabled = false; } });
document.getElementById("close-decision").addEventListener("click", closeDecision); document.getElementById("cancel-decision").addEventListener("click", closeDecision);
document.getElementById("decision-form").addEventListener("submit", async function (event) { event.preventDefault(); if (!pendingDecision) return; var button = document.getElementById("confirm-decision"); button.disabled = true; try { await api("/claims/" + pendingDecision.claim.id + "/decision", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ approved: pendingDecision.approved, reason: document.getElementById("decision-reason").value }) }); closeDecision(); await refreshPortal(); } catch (error) { document.getElementById("decision-claim").textContent = error.message; } finally { button.disabled = false; } });
window.addEventListener("load", async function () { lucide.createIcons(); try { showPortal(await api("/api/auth/me")); } catch (_) {} window.setInterval(function () { if (currentUser) refreshPortal(); }, 15000); });
