package io.github.telegramenhance.storage;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Remove only the schema15 slice when fixtures deliberately reconstruct an older schema. */
public final class DownloadRuleMigrationFixture {
    private DownloadRuleMigrationFixture() { }
    public static void drop(Statement statement)throws SQLException {
        for(String table:List.of("download_rule_media","download_rule_tasks","download_rule_posts","download_rule_scans","download_rule_versions","download_rules"))statement.execute("DROP TABLE "+table);
    }
}
