# TraceBuddy

Modular OTEL Trace Analyzer with support for multiple backends and AI-powered insights.

## Features

- **Multi-Backend Support**: Azure Monitor, Loki, Jaeger (extensible)
- **VCS Integration**: GitHub, Azure DevOps (extensible)
- **AI Analysis**: OpenAI, Claude, Gemini, Local models (extensible)
- **Hotspot Detection**: Automatic performance bottleneck identification
- **Error Analysis**: Comprehensive error tracking and analysis
- **Source Code Integration**: Automatic source code retrieval for analysis

## Configuration

### Engine Selection
```yaml
tracebuddy:
  engine:
    type: azure  # Options: azure, loki, jaeger
