import xml.etree.ElementTree as ET
try:
    root = ET.parse('app/build/reports/lint-results-debug.xml').getroot()
    issues = [issue for issue in root.findall('issue') if issue.get('id') in ['UnusedResources', 'UnusedImport', 'UnusedVariables', 'UNUSED_VARIABLE', 'UNUSED_PARAMETER']]
    for i in issues:
        loc = i.find('location')
        if loc is not None:
            filename = loc.get('file').split('\\\\')[-1]
            line = loc.get('line')
            print(f"{i.get('id')}: {i.get('message')} in {filename} line {line}")
except Exception as e:
    print('Error:', e)
