# Manual Security Scan Script
# Use this to run OWASP dependency check locally when needed
# Much faster with an NVD API key

echo "Running OWASP Dependency Check..."
echo ""
echo "Note: This will be VERY slow without an NVD API key."
echo "Get a free API key at: https://nvd.nist.gov/developers/request-an-api-key"
echo ""
echo "To use with API key, set environment variable:"
echo "  export NVD_API_KEY=your-api-key-here"
echo ""

if [ -n "$NVD_API_KEY" ]; then
  echo "✓ Using NVD API key (scan will be fast)"
  mvn org.owasp:dependency-check-maven:check -DnvdApiKey=$NVD_API_KEY
else
  echo "⚠ No NVD API key found (scan will be SLOW - may take 30+ minutes)"
  echo ""
  read -p "Continue without API key? (y/N) " -n 1 -r
  echo
  if [[ $REPLY =~ ^[Yy]$ ]]; then
    mvn org.owasp:dependency-check-maven:check
  else
    echo "Cancelled. Please set NVD_API_KEY environment variable."
    exit 1
  fi
fi

echo ""
echo "Report generated at: target/dependency-check-report.html"
