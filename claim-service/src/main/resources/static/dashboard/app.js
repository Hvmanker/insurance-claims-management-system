var claims = [];
var currencyFormatter = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });
var dateFormatter = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });

function showMessage(message, isError) {
  var messageElement = document.getElementById("form-message");
  messageElement.textContent = message;
  messageElement.classList.toggle("error", isError);
}

function createCell(text, className) {
  var cell = document.createElement("td");
  if (className) {
    cell.className = className;
  }
  cell.textContent = text;
  return cell;
}

function renderClaims() {
  var query = document.getElementById("claim-search").value.trim().toLowerCase();
  var visibleClaims = claims.filter(function (claim) {
    return [claim.policyNumber, claim.claimantName, claim.claimType, claim.status]
      .join(" ")
      .toLowerCase()
      .includes(query);
  });
  var body = document.getElementById("claims-table-body");
  body.replaceChildren();

  visibleClaims.forEach(function (claim) {
    var row = document.createElement("tr");
    var claimantCell = createCell(claim.claimantName, "claimant");
    var id = document.createElement("span");
    id.textContent = claim.id.slice(0, 8).toUpperCase();
    claimantCell.appendChild(id);

    var statusCell = document.createElement("td");
    var badge = document.createElement("span");
    badge.className = "status-badge " + claim.status;
    badge.textContent = claim.status;
    statusCell.appendChild(badge);

    row.append(
      claimantCell,
      createCell(claim.policyNumber),
      createCell(claim.claimType),
      createCell(currencyFormatter.format(claim.estimatedAmount), "money"),
      statusCell,
      createCell(dateFormatter.format(new Date(claim.submittedAt)), "date")
    );
    body.appendChild(row);
  });
  document.getElementById("empty-state").hidden = visibleClaims.length !== 0;
}

function updateSummary(summary) {
  document.getElementById("total-claims").textContent = summary.totalClaims;
  document.getElementById("submitted-claims").textContent = summary.submittedClaims;
  document.getElementById("settled-claims").textContent = summary.settledClaims;
  document.getElementById("estimated-exposure").textContent = currencyFormatter.format(summary.totalEstimatedAmount);
  document.getElementById("submitted-count").textContent = summary.submittedClaims;
  document.getElementById("approved-count").textContent = summary.approvedClaims;
  document.getElementById("rejected-count").textContent = summary.rejectedClaims;
  document.getElementById("settled-count").textContent = summary.settledClaims;
}

async function refreshDashboard() {
  var refreshButton = document.getElementById("refresh-button");
  refreshButton.disabled = true;
  refreshButton.querySelector("svg").classList.add("spin");
  try {
    var responses = await Promise.all([fetch("/claims"), fetch("/claims/summary")]);
    if (!responses[0].ok || !responses[1].ok) {
      throw new Error("Could not load the dashboard.");
    }
    claims = await responses[0].json();
    updateSummary(await responses[1].json());
    renderClaims();
    document.getElementById("updated-at").textContent = "Updated " + new Date().toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  } catch (error) {
    showMessage(error.message, true);
  } finally {
    refreshButton.disabled = false;
    refreshButton.querySelector("svg").classList.remove("spin");
  }
}

document.getElementById("claim-search").addEventListener("input", renderClaims);
document.getElementById("refresh-button").addEventListener("click", refreshDashboard);
document.getElementById("claim-form").addEventListener("submit", async function (event) {
  event.preventDefault();
  var form = event.currentTarget;
  var button = form.querySelector("button");
  button.disabled = true;
  showMessage("Submitting claim...", false);
  var data = Object.fromEntries(new FormData(form).entries());
  data.estimatedAmount = Number(data.estimatedAmount);

  try {
    var response = await fetch("/claims", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data)
    });
    if (!response.ok) {
      throw new Error("Claim submission could not be completed.");
    }
    form.reset();
    showMessage("Claim submitted. Workflow status will update automatically.", false);
    await refreshDashboard();
  } catch (error) {
    showMessage(error.message, true);
  } finally {
    button.disabled = false;
  }
});

window.addEventListener("load", function () {
  lucide.createIcons();
  refreshDashboard();
  window.setInterval(refreshDashboard, 15000);
});
