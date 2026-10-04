package eu.wohlben.qits.runner.protocol;

/**
 * Host → runner: "you are not the runner version I pin; this is the one, go and become it." Sent
 * right after a {@link Hello} whose {@link Hello#runnerVersion()} differs from the version the host
 * pins. From that {@code Hello} on the connection is <b>draining</b>: its {@link Ack#slots()} is 0
 * and every {@link Reserve} is answered {@link Nothing}, so the runner finishes what it already
 * holds and takes no more.
 *
 * <p>A runner is a container on its host, and becoming another version is becoming another
 * container. The runner pulls {@code image} with its own commissioned client pair as its registry
 * login, checks the pulled image's digest against {@code sha256} when the host sent one (null when
 * it did not), and — once it is quiescent — starts a successor container from it with its own
 * parameters. The new process connects and says {@code Hello} in the pinned version; the host then
 * hands it the slots and sends this one {@link Retire}.
 *
 * <p><b>THE WIRE SHAPE IS FROZEN</b>, for every installed runner of every kind, and so is {@link
 * Retire}'s and {@code Hello.runnerVersion}'s: the type {@code "upgrade"} and the fields {@code
 * version}, {@code image} and {@code sha256}, each a string, with these meanings. They are how a
 * runner of <em>any</em> older version is told to become the current one — a runner installed today
 * must still understand the frame the host sends in five years, or it can only be updated by a
 * person at the machine again. A field may be added (both codecs ignore unknown fields); none of
 * these may be renamed, retyped, removed or given a new meaning, and a change that needs one is a
 * new frame beside this one, not an edit of it.
 *
 * @param version the runner version to become; also part of the successor's container name and its
 *     version label, so the runner refuses anything outside a plain version charset.
 * @param image the full image reference to pull, possibly pinned with an {@code @sha256:} digest.
 *     Its registry host is where the runner's own client pair is presented.
 * @param sha256 the expected image digest (lowercase hex, with or without the {@code sha256:}
 *     prefix), or null when the host has none to offer.
 */
public record Upgrade(String version, String image, String sha256) implements LifecycleMessage {}
