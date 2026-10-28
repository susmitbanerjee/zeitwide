"""K.8, holidays observed by the Federal Reserve System.

Corroborates the Reserve Banks' holiday schedule: same table, published by the Board.
"""

from settlecal_pipeline.models import SourceRole
from settlecal_pipeline.sources.base import SourceDefinition
from settlecal_pipeline.sources.fed_holidays import FedHolidayTableParser

SOURCE = SourceDefinition(
    id="federalreserve-k8",
    publisher="Board of Governors of the Federal Reserve System",
    url="https://www.federalreserve.gov/aboutthefed/k8.htm",
    parser=FedHolidayTableParser(name="federalreserve_k8_table"),
    content_types=frozenset({"text/html"}),
    role=SourceRole.CORROBORATING,
)
