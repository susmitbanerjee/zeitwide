"""One module per monitored source, each defining ``SOURCE``.

A module here must:

- define ``SOURCE``, an object matching ``zeitwide_pipeline.sources.Source``;
- keep its parser pure (bytes in, observations out) and raise ``ParseError`` on any
  layout it doesn't recognise;
- come with golden tests from a stored real snapshot in ``tests/fixtures/<source id>/``.
"""
