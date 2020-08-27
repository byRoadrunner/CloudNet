package eu.cloudnetservice.cloudnet.v2.command;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * Interface denoting classes that complete inputs and return a list of possible
 * completion candidates.
 */
public interface TabCompletable {

    /**
     * This method is called when a tab completion is requested by a {@link CommandSender}.
     *
     * @param argsLength the amount of arguments currently given to the command
     * @param lastWord   the last word, given to the command
     *
     * @return a list of tab complete candidates
     */
    List<Candidate> onTab(long argsLength, String lastWord, ParsedLine parsedLine, String[] args);

}
