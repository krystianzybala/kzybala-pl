package pl.kzybala.lab.vpe;

import java.util.List;

public interface VariantRunner {
    RunResult run(List<TaskSpec> tasks) throws Exception;
}
