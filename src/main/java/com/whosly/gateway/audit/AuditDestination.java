package com.whosly.gateway.audit;
import java.io.IOException;
import java.util.List;
public interface AuditDestination { void writeBatch(List<String> records) throws IOException; }
