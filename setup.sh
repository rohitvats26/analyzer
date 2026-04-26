#!/bin/bash

echo "🤖 Setting up Copilot-Powered PR Impact Analyzer"

# Check for required tools
if ! command -v java &> /dev/null; then
    echo "❌ Java is required but not installed"
    exit 1
fi

if ! command -v mvn &> /dev/null; then
    echo "❌ Maven is required but not installed"
    exit 1
fi

# Set environment variables for Copilot
echo "📝 Setting up environment variables..."
export GITHUB_TOKEN="${GITHUB_TOKEN}"
export WEBHOOK_SECRET="${WEBHOOK_SECRET}"

# GitHub Copilot specific settings
echo "🔧 Configuring Copilot integration..."
echo ""
echo "For Copilot integration, ensure:"
echo "1. Your GitHub account has Copilot access"
echo "2. The GitHub token has these permissions:"
echo "   - repo (full)"
echo "   - workflow"
echo "   - write:packages"
echo "   - read:org"
echo ""
echo "3. Copilot is enabled for your repository"

# Check if GitHub token has required scopes
if [ -n "$GITHUB_TOKEN" ]; then
    echo "✅ GitHub token is set"
    echo "📋 Checking token scopes..."

    # Check token scopes (simplified check)
    RESPONSE=$(curl -s -H "Authorization: token $GITHUB_TOKEN" \
        https://api.github.com/user)

    if echo "$RESPONSE" | grep -q "login"; then
        echo "✅ Token is valid"
    else
        echo "❌ Token is invalid or expired"
        exit 1
    fi
else
    echo "⚠️  GITHUB_TOKEN not set"
    echo "Please set it with: export GITHUB_TOKEN=your_token"
    exit 1
fi

# Build the application
echo "🔨 Building application..."
mvn clean package -DskipTests

# Start the application
echo "🚀 Starting Copilot-powered analyzer..."
java -jar target/pr-impact-analyzer-1.0.0.jar &

echo ""
echo "✅ Setup complete!"
echo ""
echo "🤖 Copilot Integration Features:"
echo "  - Automatic PR analysis using Copilot"
echo "  - Code impact predictions"
echo "  - Test case recommendations"
echo "  - Risk assessment"
echo "  - Smart labeling"
echo ""
echo "📡 Next steps:"
echo "1. Configure GitHub webhook:"
echo "   - URL: http://your-domain:8080/webhook/github"
echo "   - Secret: same as WEBHOOK_SECRET"
echo "   - Events: Pull requests"
echo ""
echo "2. Ensure Copilot is enabled in your GitHub repo"
echo "3. Create a PR and watch Copilot analyze it!"