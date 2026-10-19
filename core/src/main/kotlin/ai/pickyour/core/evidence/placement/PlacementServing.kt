package ai.pickyour.core.evidence.placement

/** Explicit operation requirements for a component's resolved serving tuple. */
class PlacementServingRequirements(
    val selectionId: String,
    val engine: PlacementAnswer,
    val host: PlacementAnswer,
    val devices: PlacementAnswer,
) {
    init {
        placementIdentity(selectionId)
        listOf(engine, host, devices).forEach { requirePlacementField(it, PlacementField.BOOLEAN) }
    }
    override fun toString() = "PlacementServingRequirements"
}

class PlacementServingBinding internal constructor(
    val lineage: PlacementLineage,
    val engine: PlacementBinding?,
    val host: PlacementBinding?,
    val devices: PlacementBinding?,
) {
    val checks = immutablePlacementList(lineage.checks + listOfNotNull(engine?.check, host?.check, devices?.check))
    val outcome = if (lineage.outcome != PlacementOutcome.PASS) lineage.outcome
        else aggregatePlacement(listOfNotNull(engine?.check, host?.check, devices?.check).map { it.outcome })
    override fun toString() = "PlacementServingBinding"
}

/** Read the same selected graph. Adapter lineage changes the serving subject, never the graph. */
fun PlacementGraph.serving(
    current: PlacementBasis,
    q: PlacementQualifications,
    requirements: PlacementServingRequirements,
    artifactFacts: List<PlacementArtifactFacts>,
): PlacementServingBinding {
    val lineage = lineage(current, q, requirements.selectionId, artifactFacts)
    val serving = lineage.servingArtifact
    if (lineage.outcome != PlacementOutcome.PASS || serving == null)
        return PlacementServingBinding(lineage, null, null, null)
    return PlacementServingBinding(lineage,
        resolve(current, q, serving.id, PlacementRole.ENGINE, requirements.engine),
        resolve(current, q, serving.id, PlacementRole.HOST, requirements.host),
        resolve(current, q, serving.id, PlacementRole.DEVICE, requirements.devices))
}
