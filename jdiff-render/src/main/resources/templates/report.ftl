<#ftl output_format="HTML">
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>jdiff report</title>
<style>
  * { box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    margin: 2rem;
    color: #1a1a1a;
    background: #fafafa;
  }
  h1 { font-size: 1.6rem; margin-bottom: 0.25rem; }
  h2 { font-size: 1.2rem; margin-top: 2.5rem; }
  .meta-list { list-style: none; padding: 0; margin: 0 0 1.5rem; color: #444; }
  .meta-list li { margin-bottom: 0.2rem; }
  table {
    border-collapse: collapse;
    width: 100%;
    background: #fff;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  }
  th, td {
    border: 1px solid #e0e0e0;
    padding: 0.5rem 0.75rem;
    text-align: left;
    font-size: 0.88rem;
    vertical-align: top;
  }
  thead th {
    background: #f2f2f2;
    position: sticky;
    top: 0;
    z-index: 1;
  }
  tbody tr:nth-child(even) { background: #fbfbfb; }
  tr.breaking { background: #fdecea; }
  .badge {
    display: inline-block;
    padding: 0.15rem 0.6rem;
    border-radius: 999px;
    font-size: 0.75rem;
    font-weight: 600;
    color: #fff;
  }
  .badge-major { background: #c0392b; }
  .badge-minor { background: #e67e22; }
  .badge-patch { background: #27ae60; }
  .badge-none { background: #95a5a6; }
</style>
</head>
<body>
<h1>jdiff report</h1>
<ul class="meta-list">
  <li><strong>Tool:</strong> ${tool} ${toolVersion}</li>
  <li><strong>Mode:</strong> ${mode}</li>
  <li><strong>Generated at:</strong> ${generatedAt}</li>
  <#list inputEntries as entry>
  <li><strong>${entry.key}:</strong> ${entry.value}</li>
  </#list>
</ul>

<h2>Summary</h2>
<table>
  <thead>
    <tr>
      <th>Artifact</th>
      <th>Old &rarr; New</th>
      <th>SemVer</th>
      <th>Changes</th>
      <th>Breaking</th>
    </tr>
  </thead>
  <tbody>
    <#list artifacts as a>
    <tr>
      <td>${a.ga}</td>
      <td>${a.oldVersion} &rarr; ${a.newVersion}</td>
      <td><span class="badge ${a.semverBadgeClass}">${a.semverVerdict}</span></td>
      <td>${a.changeCount}</td>
      <td>${a.breakingCount}</td>
    </tr>
    </#list>
  </tbody>
</table>

<#list artifacts as a>
<h2>${a.ga}</h2>
<table>
  <thead>
    <tr>
      <th>Class</th>
      <th>Element type</th>
      <th>Member</th>
      <th>Status</th>
      <th>Change types</th>
      <th>Details</th>
      <th>Breaking</th>
      <th>SemVer</th>
      <#if showUsedBy><th>Used by</th></#if>
    </tr>
  </thead>
  <tbody>
    <#list a.changes as c>
    <tr<#if c.breaking> class="breaking"</#if>>
      <td>${c.className}</td>
      <td>${c.elementType}</td>
      <td>${c.member!""}</td>
      <td>${c.status}</td>
      <td>${c.changeTypesJoined}</td>
      <td>${c.details!""}</td>
      <td>${c.breaking?string("yes", "no")}</td>
      <td>${c.semver}</td>
      <#if showUsedBy><td>${c.usedBy}</td></#if>
    </tr>
    </#list>
  </tbody>
</table>
</#list>

</body>
</html>
