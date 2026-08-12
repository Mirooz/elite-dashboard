package be.mirooz.elitedangerous.dashboard.service.webservice.eddn;

import be.mirooz.elitedangerous.dashboard.model.registries.commander.CommanderStatus;
import be.mirooz.elitedangerous.dashboard.service.PreferencesService;
import be.mirooz.elitedangerous.eddn.EddnSchemas;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Routeur EDDN : invoqué par chaque {@code JournalEventHandler} via le décorateur
 * {@link be.mirooz.elitedangerous.dashboard.handlers.events.EddnPublishingEventHandlerDecorator},
 * il détermine le schéma cible à partir du champ {@code event}, délègue la conversion à un
 * {@link EddnEventMappers mapper typé} qui renvoie un POJO EDDN généré, puis publie via
 * {@link EddnUploader}.
 *
 * <p>Toute la logique de mapping / enrichissement / transformation spec vit dans
 * {@link EddnEventMappers}. Le flux est :</p>
 * <pre>
 *   JsonNode raw  →  mapper.mapXxx(raw)  →  EddnMessages.* POJO  →  uploader.publishMessage(...)
 * </pre>
 *
 * <p>Le routeur conserve deux responsabilités annexes :</p>
 * <ul>
 *   <li>tracker le contexte commandant ({@code SystemAddress}, {@code StarSystem}, {@code StarPos})
 *       sur les events navigationnels, pour que les mappers puissent enrichir les events qui ne
 *       les contiennent pas nativement ;</li>
 *   <li>bufferiser les {@code FSSSignalDiscovered} (spec EDDN : coalescer en un seul message
 *       {@code signals[]}, n'envoyer qu'après cross-check du {@code SystemAddress} avec le
 *       contexte — les signaux Odyssey arrivent souvent <i>avant</i> le {@code FSDJump}).</li>
 * </ul>
 */
public final class EddnJournalPublisher {

    private static final EddnJournalPublisher INSTANCE = new EddnJournalPublisher();

    private final EddnUploader uploader = EddnUploader.getInstance();
    private final CommanderStatus commanderStatus = CommanderStatus.getInstance();
    private final PreferencesService preferencesService = PreferencesService.getInstance();
    private final EddnEventMappers mappers = new EddnEventMappers(commanderStatus);

    /**
     * Buffer des {@code FSSSignalDiscovered} en attente de flush (run contigu + éventuellement
     * signaux Odyssey dont le jump n'a pas encore mis à jour le contexte).
     */
    private final List<JsonNode> pendingFssSignals = new ArrayList<>();

    private EddnJournalPublisher() {
    }

    public static EddnJournalPublisher getInstance() {
        return INSTANCE;
    }

    /**
     * Point d'entrée unique : à appeler depuis un {@code JournalEventHandler} une fois son traitement
     * métier effectué. Ne lève jamais, ne bloque jamais l'appelant.
     */
    public void publish(JsonNode jsonNode) {
        if (!preferencesService.isSendDataToEddnEnabled()) {
            return;
        }
        if (jsonNode == null || !jsonNode.isObject()) {
            return;
        }
        String event = jsonNode.path("event").asText(null);
        if (event == null) {
            return;
        }

        trackCommanderContext(event, jsonNode);

        try {
            if ("FSSSignalDiscovered".equals(event)) {
                pendingFssSignals.add(jsonNode);
                return;
            }
            // Tout autre event termine le run contigu : flush des signaux dont le SystemAddress
            // matche le contexte. Après un event navigationnel, les mismatches sont abandonnés
            // (nouvelle position confirmée). Sinon on les garde (attente du FSDJump Odyssey).
            flushPendingFssSignals(isNavigational(event));
            route(event, jsonNode);

        } catch (Exception e) {
            System.err.println("EDDN route " + event + " : " + e.getMessage());
        }
    }

    private void route(String event, JsonNode raw) throws Exception {
        if (EddnEventMappers.JOURNAL_SCHEMA_EVENTS.contains(event)) {
            send(EddnSchemas.JOURNAL_V1, mappers.mapJournal(raw));
            return;
        }
        switch (event) {
            case "ApproachSettlement":
                send(EddnSchemas.APPROACH_SETTLEMENT_V1, mappers.mapApproachSettlement(raw));
                break;
            case "CodexEntry":
                send(EddnSchemas.CODEX_ENTRY_V1, mappers.mapCodexEntry(raw));
                break;
            case "DockingDenied":
                send(EddnSchemas.DOCKING_DENIED_V1, mappers.mapDockingDenied(raw));
                break;
            case "DockingGranted":
                send(EddnSchemas.DOCKING_GRANTED_V1, mappers.mapDockingGranted(raw));
                break;
            case "FCMaterials":
                send(EddnSchemas.FC_MATERIALS_JOURNAL_V1, mappers.mapFcMaterialsJournal(raw));
                break;
            case "FSSAllBodiesFound":
                send(EddnSchemas.FSS_ALL_BODIES_FOUND_V1, mappers.mapFssAllBodiesFound(raw));
                break;
            case "FSSBodySignals":
                send(EddnSchemas.FSS_BODY_SIGNALS_V1, mappers.mapFssBodySignals(raw));
                break;
            case "FSSDiscoveryScan":
                send(EddnSchemas.FSS_DISCOVERY_SCAN_V1, mappers.mapFssDiscoveryScan(raw));
                break;
            case "NavBeaconScan":
                send(EddnSchemas.NAV_BEACON_SCAN_V1, mappers.mapNavBeaconScan(raw));
                break;
            case "ScanBaryCentre":
                send(EddnSchemas.SCAN_BARY_CENTRE_V1, mappers.mapScanBaryCentre(raw));
                break;

            // Schémas lus depuis les fichiers compagnons du jeu.
            case "Market":
                send(EddnSchemas.COMMODITY_V3, mappers.mapCommodity(EddnJournalFileReader.readMarket(), raw));
                break;
            case "Outfitting":
                send(EddnSchemas.OUTFITTING_V2, mappers.mapOutfitting(EddnJournalFileReader.readOutfitting(), raw));
                break;
            case "Shipyard":
                send(EddnSchemas.SHIPYARD_V2, mappers.mapShipyard(EddnJournalFileReader.readShipyard(), raw));
                break;
            case "NavRoute":
                send(EddnSchemas.NAV_ROUTE_V1, mappers.mapNavRoute(EddnJournalFileReader.readNavRoute(), raw));
                break;
            default:
                // Event non relayé à EDDN.
                break;
        }
    }

    /**
     * Publie le batch FSS en attente dont le {@code SystemAddress} matche le contexte commandant.
     *
     * @param dropMismatches si {@code true} (après Location / FSDJump / CarrierJump), abandonne
     *                       les signaux dont l'adresse ne correspond pas ; sinon les conserve
     *                       pour un flush ultérieur une fois le jump reçu.
     */
    private void flushPendingFssSignals(boolean dropMismatches) {
        if (pendingFssSignals.isEmpty()) {
            return;
        }
        Long ctxAddr = commanderStatus.getCurrentSystemAddress();
        List<JsonNode> matching = new ArrayList<>();
        Iterator<JsonNode> it = pendingFssSignals.iterator();
        while (it.hasNext()) {
            JsonNode raw = it.next();
            if (ctxAddr != null
                    && raw.has("SystemAddress")
                    && raw.get("SystemAddress").canConvertToLong()
                    && raw.get("SystemAddress").asLong() == ctxAddr.longValue()) {
                matching.add(raw);
                it.remove();
            } else if (dropMismatches) {
                it.remove();
            }
        }
        if (matching.isEmpty()) {
            return;
        }
        try {
            send(EddnSchemas.FSS_SIGNAL_DISCOVERED_V1, mappers.mapFssSignalDiscoveredBatch(matching));
        } catch (Exception e) {
            System.err.println("EDDN FSSSignalDiscovered batch : " + e.getMessage());
        }
    }

    /** Délègue à l'uploader en filtrant les POJOs null (mapper a décidé qu'il n'y avait rien à publier). */
    private void send(String schemaRef, Object pojo) {
        if (pojo == null) {
            return;
        }
        uploader.publishMessage(schemaRef, pojo);
    }

    // ------------------------------------------------------------------
    //  Suivi du contexte commandant : alimente CommanderStatus pour que les mappers puissent
    //  enrichir les events qui ne contiennent pas nativement StarPos / SystemAddress.
    // ------------------------------------------------------------------

    private static boolean isNavigational(String event) {
        return "FSDJump".equals(event)
                || "Location".equals(event)
                || "CarrierJump".equals(event);
    }

    private void trackCommanderContext(String event, JsonNode raw) {
        if (!isNavigational(event)) {
            return;
        }
        if (raw.has("SystemAddress") && raw.get("SystemAddress").canConvertToLong()) {
            commanderStatus.setCurrentSystemAddress(raw.get("SystemAddress").asLong());
        }
        String starSystem = raw.path("StarSystem").asText("");
        if (!starSystem.isBlank()) {
            commanderStatus.setCurrentStarSystem(starSystem);
        }
        JsonNode pos = raw.get("StarPos");
        if (pos != null && pos.isArray() && pos.size() == 3) {
            double[] xyz = new double[] {
                    pos.get(0).asDouble(),
                    pos.get(1).asDouble(),
                    pos.get(2).asDouble()
            };
            commanderStatus.setCurrentStarPos(xyz);
        }
    }
}
